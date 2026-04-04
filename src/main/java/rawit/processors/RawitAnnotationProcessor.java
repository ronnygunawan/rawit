package rawit.processors;

import rawit.processors.codegen.JavaPoetGenerator;
import rawit.processors.inject.BytecodeInjector;
import rawit.processors.inject.OverloadResolver;
import rawit.processors.merge.MergeTreeBuilder;
import rawit.processors.model.AnnotatedMethod;
import rawit.processors.model.MergeTree;
import rawit.processors.model.OverloadGroup;
import rawit.processors.model.Parameter;
import rawit.processors.validation.ElementValidator;
import rawit.processors.validation.ValidationResult;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.nio.file.Path;
import java.util.*;

/**
 * Annotation processor for {@code @Invoker} and {@code @Constructor}.
 *
 * <p>Pipeline per round:
 * <ol>
 *   <li>Collect elements annotated with {@code @Invoker} and {@code @Constructor}.</li>
 *   <li>Validate each element via {@link ElementValidator}; skip invalid elements.</li>
 *   <li>Build {@link AnnotatedMethod} models from valid elements.</li>
 *   <li>Group into {@link OverloadGroup} instances by enclosing class + method name.</li>
 *   <li>Build a {@link MergeTree} per group via {@link MergeTreeBuilder}; skip on conflict.</li>
 *   <li>Generate source files via {@link JavaPoetGenerator}.</li>
 *   <li>Inject parameterless overloads via {@link BytecodeInjector} once per enclosing class.</li>
 * </ol>
 */
@SupportedOptions("invoker.debug")
public class RawitAnnotationProcessor extends AbstractProcessor {

    /** Creates a new {@code RawitAnnotationProcessor}. */
    public RawitAnnotationProcessor() {}

    private static final String INVOKER_ANNOTATION_FQN = "rawit.Invoker";
    private static final String CONSTRUCTOR_ANNOTATION_FQN = "rawit.Constructor";

    private Messager messager;
    private ElementValidator elementValidator;
    private JavaPoetGenerator javaPoetGenerator;
    private BytecodeInjector bytecodeInjector;
    private OverloadResolver overloadResolver;

    @Override
    public final synchronized void init(final ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        this.elementValidator = new ElementValidator();
        this.javaPoetGenerator = new JavaPoetGenerator(messager);
        this.bytecodeInjector = new BytecodeInjector();
        this.overloadResolver = new OverloadResolver();
    }

    @Override
    public final Set<String> getSupportedAnnotationTypes() {
        return Set.of(INVOKER_ANNOTATION_FQN, CONSTRUCTOR_ANNOTATION_FQN);
    }

    @Override
    public final SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public final boolean process(final Set<? extends TypeElement> annotations,
                                 final RoundEnvironment roundEnv) {
        if (annotations.isEmpty()) {
            return false;
        }

        final boolean debug = isDebugEnabled();

        // --- Stage 1: Collect and validate annotated elements ---
        final List<AnnotatedMethod> validMethods = new ArrayList<>();

        for (final TypeElement annotation : annotations) {
            final String fqn = annotation.getQualifiedName().toString();
            if (!INVOKER_ANNOTATION_FQN.equals(fqn) && !CONSTRUCTOR_ANNOTATION_FQN.equals(fqn)) {
                continue;
            }

            for (final Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                final ValidationResult result = elementValidator.validate(element, messager);
                if (result instanceof ValidationResult.Invalid) {
                    continue; // skip invalid elements
                }

                if (!(element instanceof ExecutableElement exec)) {
                    continue;
                }

                final AnnotatedMethod model = buildAnnotatedMethod(exec);
                if (model != null) {
                    validMethods.add(model);
                    if (debug) {
                        messager.printMessage(Diagnostic.Kind.NOTE,
                                "[invoker.debug] Validated element: "
                                        + model.enclosingClassName() + "#" + model.methodName()
                                        + " params=" + model.parameters().size());
                    }
                }
            }
        }

        if (validMethods.isEmpty()) {
            return false;
        }

        // --- Stage 2: Group into OverloadGroups ---
        // Key: enclosingClassName + "\0" + groupName + "\0" + annotationKind
        // The annotation kind is included so that a @Invoker constructor and a @Constructor
        // constructor in the same class are NOT merged into the same group (they have different
        // entry-point names, stage interface suffixes, and injection strategies).
        final Map<String, List<AnnotatedMethod>> groupMap = new LinkedHashMap<>();
        for (final AnnotatedMethod m : validMethods) {
            final String annotationKind = m.isConstructorAnnotation() ? "CONSTRUCTOR" : "INVOKER";
            final String key = m.enclosingClassName() + "\0" + m.methodName() + "\0" + annotationKind;
            groupMap.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
        }

        if (debug) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "[invoker.debug] Formed " + groupMap.size() + " overload group(s)");
        }

        // --- Stage 3: Build MergeTrees ---
        final List<MergeTree> allTrees = new ArrayList<>();
        final MergeTreeBuilder mergeTreeBuilder = new MergeTreeBuilder(messager);

        for (final Map.Entry<String, List<AnnotatedMethod>> entry : groupMap.entrySet()) {
            final List<AnnotatedMethod> members = entry.getValue();
            // Derive enclosingClassName and groupName from the first member
            final AnnotatedMethod first = members.get(0);
            final OverloadGroup group = new OverloadGroup(
                    first.enclosingClassName(), first.methodName(), members);

            final MergeTree tree = mergeTreeBuilder.build(group);
            if (tree == null) {
                // Conflict detected — MergeTreeBuilder already emitted ERROR
                if (debug) {
                    messager.printMessage(Diagnostic.Kind.NOTE,
                            "[invoker.debug] Skipping group due to merge conflict: "
                                    + first.enclosingClassName() + "#" + first.methodName());
                }
                continue;
            }

            allTrees.add(tree);
            if (debug) {
                messager.printMessage(Diagnostic.Kind.NOTE,
                        "[invoker.debug] Built MergeTree for group: "
                                + first.enclosingClassName() + "#" + first.methodName());
            }
        }

        if (allTrees.isEmpty()) {
            return false;
        }

        // --- Stage 4: Generate source files via JavaPoet ---
        // Source generation runs for ALL valid trees regardless of whether the .class file
        // exists. Only bytecode injection is gated on the .class file being present.
        // Group trees by enclosing class (needed for injection step).
        final Map<String, List<MergeTree>> treesByClass = new LinkedHashMap<>();
        for (final MergeTree tree : allTrees) {
            treesByClass.computeIfAbsent(tree.group().enclosingClassName(), k -> new ArrayList<>())
                    .add(tree);
        }

        if (debug) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "[invoker.debug] Generating source files for " + allTrees.size() + " tree(s)");
        }
        javaPoetGenerator.generate(allTrees, processingEnv);

        // --- Stage 5: Inject parameterless overloads via ASM, once per enclosing class ---
        // In a standard single-pass javac/Maven compile, annotation processing runs before
        // .class files are written, so the .class file may not exist yet. We skip injection
        // silently in that case — users who need injection must run a two-pass compile
        // (see README for details).
        for (final Map.Entry<String, List<MergeTree>> entry : treesByClass.entrySet()) {
            final String enclosingClassName = entry.getKey();
            final List<MergeTree> classTrees = entry.getValue();

            final Optional<Path> classFilePath = overloadResolver.resolve(enclosingClassName, processingEnv);
            if (classFilePath.isEmpty()) {
                if (debug) {
                    messager.printMessage(Diagnostic.Kind.NOTE,
                            "[invoker.debug] .class file not found for "
                                    + enclosingClassName.replace('/', '.')
                                    + " — skipping bytecode injection (run a two-pass compile for injection)");
                }
                continue;
            }

            if (debug) {
                messager.printMessage(Diagnostic.Kind.NOTE,
                        "[invoker.debug] Injecting overloads into: " + classFilePath.get());
            }

            bytecodeInjector.inject(classFilePath.get(), classTrees, processingEnv);

            // Emit NOTE on success per requirement 10.1 and 16.5
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "RawitAnnotationProcessor: processed " + enclosingClassName.replace('/', '.')
                            + " — injected " + classTrees.size() + " overload group(s)");
        }

        return false;
    }

    // =========================================================================
    // Element → AnnotatedMethod conversion
    // =========================================================================

    /**
     * Converts a validated {@link ExecutableElement} to an {@link AnnotatedMethod} model.
     *
     * @param exec the validated executable element
     * @return the model, or {@code null} if the enclosing element is not a type
     */
    private AnnotatedMethod buildAnnotatedMethod(final ExecutableElement exec) {
        final Element enclosing = exec.getEnclosingElement();
        if (!(enclosing instanceof TypeElement typeElement)) {
            return null;
        }

        final String enclosingClassName = toBinaryName(typeElement);
        final boolean isConstructor = exec.getKind() == ElementKind.CONSTRUCTOR;
        final boolean isConstructorAnnotation = exec.getAnnotation(rawit.Constructor.class) != null;
        final String methodName = isConstructor ? "<init>" : exec.getSimpleName().toString();
        final boolean isStatic = exec.getModifiers().contains(Modifier.STATIC);

        final List<Parameter> parameters = new ArrayList<>();
        for (final VariableElement param : exec.getParameters()) {
            final String name = param.getSimpleName().toString();
            final String descriptor = toTypeDescriptor(param.asType());
            parameters.add(new Parameter(name, descriptor));
        }

        final String returnTypeDescriptor = isConstructor
                ? "V"
                : toTypeDescriptor(exec.getReturnType());

        final List<String> checkedExceptions = new ArrayList<>();
        for (final TypeMirror thrown : exec.getThrownTypes()) {
            checkedExceptions.add(toInternalName(thrown));
        }

        final int accessFlags = resolveAccessFlags(exec);

        return new AnnotatedMethod(
                enclosingClassName,
                methodName,
                isStatic,
                isConstructor,
                isConstructorAnnotation,
                parameters,
                returnTypeDescriptor,
                checkedExceptions,
                accessFlags);
    }

    /**
     * Converts a {@link TypeElement} to a JVM binary name with slashes.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code com.example.Foo} → {@code com/example/Foo}</li>
     *   <li>{@code com.example.Outer.Inner} → {@code com/example/Outer$Inner}</li>
     * </ul>
     *
     * <p>Uses the enclosing-element chain to correctly handle nested types (using {@code $}
     * as the separator between outer and inner class names).
     */
    private static String toBinaryName(final TypeElement typeElement) {
        final List<String> typeNames = new ArrayList<>();
        Element current = typeElement;
        PackageElement pkg = null;

        // Walk up the enclosing element chain, collecting type simple names (innermost first)
        while (current != null && current.getKind() != ElementKind.PACKAGE) {
            if (current instanceof TypeElement te) {
                typeNames.add(te.getSimpleName().toString());
            }
            current = current.getEnclosingElement();
        }
        if (current instanceof PackageElement pe) {
            pkg = pe;
        }

        // Reverse so we have outermost type first
        Collections.reverse(typeNames);

        final StringBuilder binaryName = new StringBuilder();
        if (pkg != null && !pkg.isUnnamed()) {
            binaryName.append(pkg.getQualifiedName().toString());
            if (!typeNames.isEmpty()) {
                binaryName.append('.');
            }
        }
        if (!typeNames.isEmpty()) {
            binaryName.append(typeNames.get(0));
            for (int i = 1; i < typeNames.size(); i++) {
                binaryName.append('$').append(typeNames.get(i));
            }
        }

        // Convert package separators to JVM internal-name slashes
        return binaryName.toString().replace('.', '/');
    }

    /**
     * Converts a {@link TypeMirror} to a JVM type descriptor string.
     *
     * <p>For declared (reference) types, uses {@link javax.lang.model.util.Elements#getBinaryName}
     * on the underlying {@link TypeElement} so that nested types are rendered with {@code $}
     * rather than {@code .} (e.g. {@code Lcom/example/Outer$Inner;}).
     */
    private String toTypeDescriptor(final TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN -> "Z";
            case BYTE    -> "B";
            case CHAR    -> "C";
            case SHORT   -> "S";
            case INT     -> "I";
            case LONG    -> "J";
            case FLOAT   -> "F";
            case DOUBLE  -> "D";
            case VOID    -> "V";
            case ARRAY   -> {
                final javax.lang.model.type.ArrayType arr = (javax.lang.model.type.ArrayType) type;
                yield "[" + toTypeDescriptor(arr.getComponentType());
            }
            case DECLARED -> {
                final javax.lang.model.type.DeclaredType declared = (javax.lang.model.type.DeclaredType) type;
                final TypeElement typeElement = (TypeElement) declared.asElement();
                // getBinaryName uses '$' for nested types (e.g. "com.example.Outer$Inner")
                final String binaryName = processingEnv.getElementUtils()
                        .getBinaryName(typeElement).toString();
                yield "L" + binaryName.replace('.', '/') + ";";
            }
            default -> {
                // Type variables and wildcards are erased to Object at the JVM level.
                // Other unknown kinds also fall back to Object to avoid invalid descriptors.
                yield "Ljava/lang/Object;";
            }
        };
    }

    /**
     * Converts a {@link TypeMirror} to an internal (slash-separated) class name.
     * Used for checked exception types.
     *
     * <p>For declared types, uses {@link javax.lang.model.util.Elements#getBinaryName} to
     * correctly handle nested exception types (e.g. {@code com/example/Outer$MyException}).
     */
    private String toInternalName(final TypeMirror type) {
        if (type.getKind() == javax.lang.model.type.TypeKind.DECLARED) {
            final javax.lang.model.type.DeclaredType declared = (javax.lang.model.type.DeclaredType) type;
            final TypeElement typeElement = (TypeElement) declared.asElement();
            return processingEnv.getElementUtils()
                    .getBinaryName(typeElement).toString().replace('.', '/');
        }
        final String raw = type.toString();
        final int lt = raw.indexOf('<');
        final String erased = lt >= 0 ? raw.substring(0, lt) : raw;
        return erased.replace('.', '/');
    }

    /**
     * Resolves ASM-compatible access flags from the element's modifiers.
     */
    private static int resolveAccessFlags(final ExecutableElement exec) {
        final Set<Modifier> mods = exec.getModifiers();
        int flags = 0;
        if (mods.contains(Modifier.PUBLIC))    flags |= 0x0001; // ACC_PUBLIC
        if (mods.contains(Modifier.PROTECTED)) flags |= 0x0004; // ACC_PROTECTED
        if (mods.contains(Modifier.STATIC))    flags |= 0x0008; // ACC_STATIC
        // package-private: no visibility flag set (flags == 0 or just ACC_STATIC)
        return flags;
    }

    /**
     * Returns {@code true} when the {@code invoker.debug} processor option is set to {@code "true"}.
     */
    private boolean isDebugEnabled() {
        final String opt = processingEnv.getOptions().get("invoker.debug");
        return "true".equalsIgnoreCase(opt);
    }
}
