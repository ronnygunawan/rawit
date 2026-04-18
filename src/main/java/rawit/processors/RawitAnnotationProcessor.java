package rawit.processors;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import rawit.processors.ast.AstEntryPointInjector;
import rawit.processors.codegen.JavaPoetGenerator;
import rawit.processors.getter.GetterCollisionDetector;
import rawit.processors.getter.GetterNameResolver;
import rawit.processors.inject.BytecodeInjector;
import rawit.processors.inject.GetterBytecodeInjector;
import rawit.processors.inject.OverloadResolver;
import rawit.processors.merge.MergeTreeBuilder;
import rawit.processors.model.AnnotatedField;
import rawit.processors.model.AnnotatedMethod;
import rawit.processors.model.MergeTree;
import rawit.processors.model.OverloadGroup;
import rawit.processors.model.Parameter;
import rawit.processors.model.TagInfo;
import rawit.processors.tagged.TagDiscoverer;
import rawit.processors.tagged.TagResolver;
import rawit.processors.tagged.TaggedValueAnalyzer;
import rawit.processors.validation.ElementValidator;
import rawit.processors.validation.GetterValidator;
import rawit.processors.validation.ValidationResult;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
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
 *
 * <p>When running under javac, the processor registers a {@link TaskListener} that fires after
 * each {@code .class} file is written (GENERATE phase). This enables single-pass compilation —
 * no multi-pass setup is required. When not running under javac (e.g. ECJ), the processor
 * falls back to injecting immediately if the {@code .class} file already exists (multi-pass
 * compatible).
 */
@SupportedOptions("invoker.debug")
public class RawitAnnotationProcessor extends AbstractProcessor {

    /** Creates a new {@code RawitAnnotationProcessor}. */
    public RawitAnnotationProcessor() {}

    private static final String INVOKER_ANNOTATION_FQN = "rawit.Invoker";
    private static final String CONSTRUCTOR_ANNOTATION_FQN = "rawit.Constructor";
    private static final String GETTER_ANNOTATION_FQN = "rawit.Getter";

    /** Pending invoker/constructor injections deferred until the GENERATE phase. */
    private final Map<String, List<MergeTree>> pendingInvokerInjections = new LinkedHashMap<>();
    /** Pending getter injections deferred until the GENERATE phase. */
    private final Map<String, List<AnnotatedField>> pendingGetterInjections = new LinkedHashMap<>();
    /** Compilation units already analyzed for tagged value safety (prevents duplicate warnings across rounds). */
    private final java.util.Set<java.net.URI> analyzedTaggedValueUnits = new java.util.HashSet<>();
    /** Cached tag map discovered across rounds (avoids re-scanning enclosed elements). */
    private final Map<String, TagInfo> cachedTagMap = new LinkedHashMap<>();
    /** Annotation FQNs known not to be tag annotations (negative lookup cache). */
    private final java.util.Set<String> notTagAnnotations = new java.util.HashSet<>();
    /**
     * {@code true} when a {@link TaskListener} was successfully registered on the underlying
     * {@link JavacTask}, enabling single-pass deferred injection.
     */
    private boolean useTaskListener = false;

    private Messager messager;
    private ElementValidator elementValidator;
    private JavaPoetGenerator javaPoetGenerator;
    private BytecodeInjector bytecodeInjector;
    private OverloadResolver overloadResolver;
    private GetterValidator getterValidator;
    private GetterNameResolver getterNameResolver;
    private GetterCollisionDetector getterCollisionDetector;
    private GetterBytecodeInjector getterBytecodeInjector;
    /**
     * Best-effort AST injector that adds entry-point method stubs into the
     * original class's javac AST so that IntelliSense resolves them during
     * source-level analysis.  {@code null} when not running under javac.
     */
    private AstEntryPointInjector astEntryPointInjector;

    @Override
    public final synchronized void init(final ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        this.elementValidator = new ElementValidator();
        this.javaPoetGenerator = new JavaPoetGenerator(messager);
        this.bytecodeInjector = new BytecodeInjector();
        this.overloadResolver = new OverloadResolver();
        this.getterValidator = new GetterValidator();
        this.getterNameResolver = new GetterNameResolver();
        this.getterCollisionDetector = new GetterCollisionDetector();
        this.getterBytecodeInjector = new GetterBytecodeInjector();

        // Try to register a post-generate TaskListener for single-pass injection.
        // When running under javac, this allows bytecode injection in a single compilation
        // pass because the listener fires after each .class file is written (GENERATE phase).
        // Falls back gracefully when not running under javac (e.g. ECJ).
        try {
            final JavacTask javacTask = JavacTask.instance(processingEnv);
            javacTask.addTaskListener(createPostGenerateListener());
            useTaskListener = true;
        } catch (final IllegalArgumentException ignored) {
            // Not running under javac — fall back to multi-pass (expected on non-javac compilers)
        } catch (final Exception e) {
            // Unexpected error registering TaskListener — fall back to multi-pass
            if (isDebugEnabled()) {
                messager.printMessage(Diagnostic.Kind.NOTE,
                        "[invoker.debug] Could not register post-generate TaskListener: "
                                + e.getClass().getName() + " — " + e.getMessage()
                                + ". Multi-pass compile required.");
            }
        }

        // Try to create the AST entry-point injector (javac-only, best-effort).
        // This adds method stubs to the original class's javac AST so that
        // IntelliSense can resolve them during source analysis without a prior
        // full recompile.  Returns null on non-javac compilers or on any
        // reflection failure — no correctness impact either way.
        this.astEntryPointInjector = AstEntryPointInjector.tryCreate(processingEnv);
    }

    @Override
    public final Set<String> getSupportedAnnotationTypes() {
        // Use "*" so the processor is invoked even when only user-defined tag
        // annotations (meta-annotated with @TaggedValue) are present in source.
        // Without this, downstream projects that consume tag annotations from a
        // library JAR would never trigger the TaggedValueAnalyzer.
        return Set.of("*");
    }

    @Override
    public final SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public final boolean process(final Set<? extends TypeElement> annotations,
                                 final RoundEnvironment roundEnv) {
        final boolean debug = isDebugEnabled();

        // --- @TaggedValue processing ---
        // Merge newly discovered tags into the cached map (avoids re-scanning in later rounds).
        final TagDiscoverer tagDiscoverer = new TagDiscoverer();
        final Map<String, TagInfo> roundTagMap = tagDiscoverer.discover(roundEnv, processingEnv);
        cachedTagMap.putAll(roundTagMap);

        // Also scan the annotations set for tag annotations from dependency JARs.
        // Skip annotations already known to be tags or known not to be tags.
        for (final TypeElement annotation : annotations) {
            final String fqn = annotation.getQualifiedName().toString();
            if (!cachedTagMap.containsKey(fqn) && !notTagAnnotations.contains(fqn)) {
                final TagInfo discovered = TagResolver.lazyDiscover(annotation, cachedTagMap);
                if (discovered == null) {
                    notTagAnnotations.add(fqn);
                }
            }
        }

        if (!cachedTagMap.isEmpty()) {
            final TaggedValueAnalyzer taggedValueAnalyzer = new TaggedValueAnalyzer();
            taggedValueAnalyzer.analyzeRound(cachedTagMap, roundEnv, processingEnv, analyzedTaggedValueUnits);
        }

        if (annotations.isEmpty()) {
            return false;
        }

        // Early exit: if no rawit annotations are present, skip getter/invoker processing.
        // Since getSupportedAnnotationTypes() returns "*", the processor runs on every round.
        final boolean hasRawitAnnotations = annotations.stream().anyMatch(a -> {
            final String fqn = a.getQualifiedName().toString();
            return INVOKER_ANNOTATION_FQN.equals(fqn)
                    || CONSTRUCTOR_ANNOTATION_FQN.equals(fqn)
                    || GETTER_ANNOTATION_FQN.equals(fqn);
        });
        if (!hasRawitAnnotations) {
            return false;
        }

        // --- @Getter processing ---
        final List<AnnotatedField> validGetterFields = new ArrayList<>();
        final Map<TypeElement, List<AnnotatedField>> getterFieldsByClass = new LinkedHashMap<>();

        for (final TypeElement annotation : annotations) {
            if (!GETTER_ANNOTATION_FQN.equals(annotation.getQualifiedName().toString())) {
                continue;
            }

            for (final Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                // Step 1: Validate
                final ValidationResult result = getterValidator.validate(element, messager);
                if (result instanceof ValidationResult.Invalid) {
                    continue;
                }

                // Step 2: Build AnnotatedField model
                if (!(element instanceof VariableElement varElement)) {
                    continue;
                }
                final Element enclosing = varElement.getEnclosingElement();
                if (!(enclosing instanceof TypeElement enclosingType)) {
                    continue;
                }

                final String enclosingClassName = toBinaryName(enclosingType);
                final String fieldName = varElement.getSimpleName().toString();
                final String fieldTypeDescriptor = toTypeDescriptor(varElement.asType());
                final String fieldTypeSignature = toTypeSignature(varElement.asType());
                final boolean isStatic = varElement.getModifiers().contains(Modifier.STATIC);
                final String getterName = getterNameResolver.resolve(fieldName, fieldTypeDescriptor);

                final AnnotatedField field = new AnnotatedField(
                        enclosingClassName, fieldName, fieldTypeDescriptor,
                        fieldTypeSignature, isStatic, getterName);

                getterFieldsByClass.computeIfAbsent(enclosingType, k -> new ArrayList<>()).add(field);
            }
        }

        // Step 3: Collision detection per class
        for (final Map.Entry<TypeElement, List<AnnotatedField>> entry : getterFieldsByClass.entrySet()) {
            final TypeElement enclosingClass = entry.getKey();
            final List<AnnotatedField> classFields = entry.getValue();

            final List<AnnotatedField> passed = getterCollisionDetector.detect(
                    classFields, enclosingClass, messager,
                    processingEnv.getTypeUtils());

            validGetterFields.addAll(passed);

            // AST injection — adds getter stubs to the class's javac AST so that
            // IntelliSense resolves them from source analysis (best-effort, javac only).
            if (astEntryPointInjector != null) {
                for (final AnnotatedField field : passed) {
                    astEntryPointInjector.injectGetterMethod(enclosingClass, field);
                }
            }
        }

        // Step 4: Group valid fields by enclosing class and inject
        final Map<String, List<AnnotatedField>> gettersByClassName = new LinkedHashMap<>();
        for (final AnnotatedField field : validGetterFields) {
            gettersByClassName.computeIfAbsent(field.enclosingClassName(), k -> new ArrayList<>())
                    .add(field);
        }

        for (final Map.Entry<String, List<AnnotatedField>> entry : gettersByClassName.entrySet()) {
            final String enclosingClassName = entry.getKey();
            final List<AnnotatedField> classFields = entry.getValue();

            // Step 5: Resolve .class file path
            final Optional<Path> classFilePath = overloadResolver.resolve(enclosingClassName, processingEnv);
            if (classFilePath.isPresent()) {
                // File exists (e.g. multi-pass compile) — inject immediately
                if (debug) {
                    messager.printMessage(Diagnostic.Kind.NOTE,
                            "[getter.debug] Injecting @Getter methods into: " + classFilePath.get());
                }
                getterBytecodeInjector.inject(classFilePath.get(), classFields, processingEnv);
            } else if (useTaskListener) {
                // File not yet written — defer injection until after the GENERATE phase
                pendingGetterInjections.merge(enclosingClassName, new ArrayList<>(classFields), (a, b) -> {
                    a.addAll(b);
                    return a;
                });
                if (debug) {
                    messager.printMessage(Diagnostic.Kind.NOTE,
                            "[getter.debug] Deferred @Getter injection for: "
                                    + enclosingClassName.replace('/', '.'));
                }
            } else {
                if (debug) {
                    messager.printMessage(Diagnostic.Kind.NOTE,
                            "[getter.debug] .class file not found for "
                                    + enclosingClassName.replace('/', '.')
                                    + " — skipping @Getter bytecode injection");
                }
            }
        }

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

                final AnnotatedMethod model;
                if (element instanceof TypeElement typeElement
                        && typeElement.getKind() == ElementKind.RECORD) {
                    model = buildAnnotatedMethodFromRecord(typeElement);
                } else if (element instanceof ExecutableElement exec) {
                    model = buildAnnotatedMethod(exec);
                } else {
                    continue;
                }

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

        // --- Stage 4b: AST entry-point injection (javac only, best-effort) ---
        // Adds the parameterless entry-point method stubs to the original class's
        // javac AST so that IntelliSense can resolve calc.add() / Point.constructor()
        // during source analysis without requiring a prior recompile.
        // Falls back silently when astEntryPointInjector is null (non-javac) or when
        // the AST is not yet available (e.g. -proc:only mode).
        if (astEntryPointInjector != null) {
            for (final MergeTree tree : allTrees) {
                final String dotName = tree.group().enclosingClassName().replace('/', '.');
                final javax.lang.model.element.TypeElement te =
                        processingEnv.getElementUtils().getTypeElement(dotName);
                if (te != null) {
                    astEntryPointInjector.injectInvokerEntryPoint(te, tree);
                }
            }
        }

        // --- Stage 5: Inject parameterless overloads via ASM, once per enclosing class ---
        // When a TaskListener is registered (javac), injection is deferred until after the
        // GENERATE phase so that .class files are guaranteed to exist. When running without
        // a TaskListener (non-javac compilers), injection happens immediately if the .class
        // file already exists (multi-pass compatible) or is skipped silently (single-pass
        // not supported on non-javac compilers).
        for (final Map.Entry<String, List<MergeTree>> entry : treesByClass.entrySet()) {
            final String enclosingClassName = entry.getKey();
            final List<MergeTree> classTrees = entry.getValue();

            final Optional<Path> classFilePath = overloadResolver.resolve(enclosingClassName, processingEnv);
            if (classFilePath.isPresent()) {
                // File exists (e.g. multi-pass compile) — inject immediately
                if (debug) {
                    messager.printMessage(Diagnostic.Kind.NOTE,
                            "[invoker.debug] Injecting overloads into: " + classFilePath.get());
                }
                bytecodeInjector.inject(classFilePath.get(), classTrees, processingEnv);
                messager.printMessage(Diagnostic.Kind.NOTE,
                        "RawitAnnotationProcessor: processed " + enclosingClassName.replace('/', '.')
                                + " — injected " + classTrees.size() + " overload group(s)");
            } else if (useTaskListener) {
                // File not yet written — defer injection until after the GENERATE phase
                pendingInvokerInjections.merge(enclosingClassName, new ArrayList<>(classTrees), (a, b) -> {
                    a.addAll(b);
                    return a;
                });
                if (debug) {
                    messager.printMessage(Diagnostic.Kind.NOTE,
                            "[invoker.debug] Deferred overload injection for: "
                                    + enclosingClassName.replace('/', '.'));
                }
            } else {
                if (debug) {
                    messager.printMessage(Diagnostic.Kind.NOTE,
                            "[invoker.debug] .class file not found for "
                                    + enclosingClassName.replace('/', '.')
                                    + " — skipping bytecode injection");
                }
            }
        }

        return false;
    }

    // =========================================================================
    // Post-generate injection (single-pass support)
    // =========================================================================

    /**
     * Creates a {@link TaskListener} that injects bytecode into {@code .class} files after
     * the GENERATE phase, enabling single-pass compilation.
     *
     * <p>The listener fires for every GENERATE event. For classes that have pending injections
     * registered during annotation processing, it resolves the written {@code .class} file and
     * delegates to {@link BytecodeInjector} or {@link GetterBytecodeInjector}.
     */
    private TaskListener createPostGenerateListener() {
        return new TaskListener() {
            @Override
            public void finished(final TaskEvent e) {
                if (e.getKind() != TaskEvent.Kind.GENERATE) return;
                final TypeElement typeElement = e.getTypeElement();
                if (typeElement == null) return;

                final String binaryName = toBinaryName(typeElement);
                final Optional<Path> classFilePath =
                        overloadResolver.resolve(binaryName, processingEnv);

                // Process getter injections
                final List<AnnotatedField> fields = pendingGetterInjections.remove(binaryName);
                if (fields != null && classFilePath.isPresent()) {
                    getterBytecodeInjector.inject(classFilePath.get(), fields, processingEnv);
                }

                // Process invoker/constructor injections
                final List<MergeTree> trees = pendingInvokerInjections.remove(binaryName);
                if (trees != null && classFilePath.isPresent()) {
                    bytecodeInjector.inject(classFilePath.get(), trees, processingEnv);
                    messager.printMessage(Diagnostic.Kind.NOTE,
                            "RawitAnnotationProcessor: processed " + binaryName.replace('/', '.')
                                    + " — injected " + trees.size() + " overload group(s)");
                }
            }
        };
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
            final List<String> tagFqns = extractTagAnnotationFqns(param);
            parameters.add(new Parameter(name, descriptor, tagFqns));
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
     * Builds an {@link AnnotatedMethod} from a {@code @Constructor}-annotated record type.
     *
     * <p>Derives the canonical constructor parameters from the record's components in
     * declaration order. Sets {@code methodName="<init>"}, {@code isConstructor=true},
     * {@code isConstructorAnnotation=true}, {@code returnTypeDescriptor="V"}, and
     * {@code checkedExceptions=List.of()}.
     *
     * @param recordElement the validated record type element
     * @return the model
     */
    private AnnotatedMethod buildAnnotatedMethodFromRecord(final TypeElement recordElement) {
        final String enclosingClassName = toBinaryName(recordElement);

        // Find the canonical constructor to extract tag annotations from its parameters.
        // Record component annotations with @Target(PARAMETER) are propagated to the
        // canonical constructor parameters, not to the RecordComponentElement itself.
        final List<? extends VariableElement> canonicalParams = findCanonicalConstructorParams(recordElement);

        final List<Parameter> parameters = new ArrayList<>();
        final List<? extends RecordComponentElement> components = recordElement.getRecordComponents();
        for (int i = 0; i < components.size(); i++) {
            final RecordComponentElement comp = components.get(i);
            final String name = comp.getSimpleName().toString();
            final String descriptor = toTypeDescriptor(comp.asType());
            // Try record component first, then fall back to canonical constructor parameter
            List<String> tagFqns = extractTagAnnotationFqns(comp);
            if (tagFqns.isEmpty() && canonicalParams != null && i < canonicalParams.size()) {
                tagFqns = extractTagAnnotationFqns(canonicalParams.get(i));
            }
            parameters.add(new Parameter(name, descriptor, tagFqns));
        }

        final int accessFlags = resolveRecordAccessFlags(recordElement);

        return new AnnotatedMethod(
                enclosingClassName,
                "<init>",
                false,
                true,
                true,
                parameters,
                "V",
                List.of(),
                accessFlags);
    }

    /**
     * Finds the canonical constructor's parameters for a record type.
     * The canonical constructor has the same parameter types as the record components.
     *
     * @param recordElement the record type element
     * @return the canonical constructor's parameters, or {@code null} if not found
     */
    private List<? extends VariableElement> findCanonicalConstructorParams(
            final TypeElement recordElement
    ) {
        final List<? extends RecordComponentElement> components = recordElement.getRecordComponents();
        for (final Element enclosed : recordElement.getEnclosedElements()) {
            if (enclosed instanceof ExecutableElement exec
                    && exec.getKind() == ElementKind.CONSTRUCTOR
                    && exec.getParameters().size() == components.size()) {
                // Verify parameter types match record component types
                boolean match = true;
                for (int i = 0; i < components.size(); i++) {
                    if (!processingEnv.getTypeUtils().isSameType(
                            exec.getParameters().get(i).asType(),
                            components.get(i).asType())) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return exec.getParameters();
                }
            }
        }
        return null;
    }

    /**
     * Extracts fully qualified names of tag annotations (annotations meta-annotated with
     * {@link rawit.TaggedValue @TaggedValue}) from the given element.
     *
     * <p>Uses mirror-based inspection rather than {@link Element#getAnnotation(Class)}
     * because {@code @TaggedValue} has {@code RetentionPolicy.CLASS}, and
     * {@code getAnnotation()} only works reliably for {@code RUNTIME}-retained annotations
     * when the annotation type comes from a pre-compiled JAR.
     *
     * @param element the element whose annotations to inspect
     * @return list of FQNs of tag annotations found on the element (empty if none)
     */
    private List<String> extractTagAnnotationFqns(final Element element) {
        final String taggedValueFqn = rawit.TaggedValue.class.getCanonicalName();
        final List<String> fqns = new ArrayList<>();
        for (final AnnotationMirror mirror : element.getAnnotationMirrors()) {
            final Element annotationType = mirror.getAnnotationType().asElement();
            if (annotationType instanceof TypeElement typeElement) {
                // Check if this annotation type is itself meta-annotated with @TaggedValue
                for (final AnnotationMirror meta : typeElement.getAnnotationMirrors()) {
                    final Element metaType = meta.getAnnotationType().asElement();
                    if (metaType instanceof TypeElement metaTypeElement
                            && metaTypeElement.getQualifiedName().contentEquals(taggedValueFqn)) {
                        fqns.add(typeElement.getQualifiedName().toString());
                        break;
                    }
                }
            }
        }
        return fqns;
    }

    /**
     * Resolves ASM-compatible access flags from a record type's modifiers.
     *
     * <p>The canonical constructor of a record inherits the visibility of the record type
     * itself (public record → ACC_PUBLIC, protected record → ACC_PROTECTED, etc.).
     *
     * @param typeElement the record type element
     * @return the ASM access flags
     */
    private static int resolveRecordAccessFlags(final TypeElement typeElement) {
        final Set<Modifier> mods = typeElement.getModifiers();
        int flags = 0;
        if (mods.contains(Modifier.PUBLIC))    flags |= 0x0001; // ACC_PUBLIC
        if (mods.contains(Modifier.PROTECTED)) flags |= 0x0004; // ACC_PROTECTED
        // package-private: no visibility flag set (flags == 0)
        return flags;
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
     * Computes the JVM generic type signature for a {@link TypeMirror}, or {@code null}
     * if the type is not generic (i.e. has no type arguments, is not a type variable,
     * and is not an array of a generic component).
     *
     * <p>This is used for the {@code fieldTypeSignature} in {@link AnnotatedField} to
     * preserve generic type information in the generated getter method's signature attribute.
     */
    private String toTypeSignature(final TypeMirror type) {
        return switch (type.getKind()) {
            case DECLARED -> {
                final DeclaredType declared = (DeclaredType) type;
                final List<? extends TypeMirror> typeArgs = declared.getTypeArguments();
                if (typeArgs.isEmpty()) {
                    yield null; // non-generic declared type
                }
                final TypeElement typeElement = (TypeElement) declared.asElement();
                final String binaryName = processingEnv.getElementUtils()
                        .getBinaryName(typeElement).toString();
                final StringBuilder sb = new StringBuilder();
                sb.append('L').append(binaryName.replace('.', '/'));
                sb.append('<');
                for (final TypeMirror arg : typeArgs) {
                    sb.append(toGenericSignatureComponent(arg));
                }
                sb.append('>').append(';');
                yield sb.toString();
            }
            case TYPEVAR -> {
                final TypeVariable tv = (TypeVariable) type;
                yield "T" + tv.asElement().getSimpleName() + ";";
            }
            case ARRAY -> {
                final ArrayType arr = (ArrayType) type;
                final String componentSig = toTypeSignature(arr.getComponentType());
                if (componentSig != null) {
                    yield "[" + componentSig;
                }
                yield null; // non-generic array
            }
            default -> null;
        };
    }

    /**
     * Converts a type argument {@link TypeMirror} to its JVM generic signature component.
     * Handles declared types (with or without type args), type variables, wildcards, and arrays.
     */
    private String toGenericSignatureComponent(final TypeMirror type) {
        return switch (type.getKind()) {
            case DECLARED -> {
                final DeclaredType declared = (DeclaredType) type;
                final TypeElement typeElement = (TypeElement) declared.asElement();
                final String binaryName = processingEnv.getElementUtils()
                        .getBinaryName(typeElement).toString();
                final List<? extends TypeMirror> typeArgs = declared.getTypeArguments();
                if (typeArgs.isEmpty()) {
                    yield "L" + binaryName.replace('.', '/') + ";";
                }
                final StringBuilder sb = new StringBuilder();
                sb.append('L').append(binaryName.replace('.', '/'));
                sb.append('<');
                for (final TypeMirror arg : typeArgs) {
                    sb.append(toGenericSignatureComponent(arg));
                }
                sb.append('>').append(';');
                yield sb.toString();
            }
            case TYPEVAR -> {
                final TypeVariable tv = (TypeVariable) type;
                yield "T" + tv.asElement().getSimpleName() + ";";
            }
            case WILDCARD -> {
                final WildcardType wc = (WildcardType) type;
                final TypeMirror extendsBound = wc.getExtendsBound();
                final TypeMirror superBound = wc.getSuperBound();
                if (extendsBound != null) {
                    yield "+" + toGenericSignatureComponent(extendsBound);
                } else if (superBound != null) {
                    yield "-" + toGenericSignatureComponent(superBound);
                } else {
                    yield "*";
                }
            }
            case ARRAY -> {
                final ArrayType arr = (ArrayType) type;
                yield "[" + toGenericSignatureComponent(arr.getComponentType());
            }
            default -> {
                // Primitives and other kinds — use descriptor form
                yield toTypeDescriptor(type);
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
