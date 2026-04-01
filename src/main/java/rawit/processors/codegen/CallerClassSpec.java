package rawit.processors.codegen;

import com.squareup.javapoet.*;
import rawit.processors.model.AnnotatedMethod;
import rawit.processors.model.MergeNode;
import rawit.processors.model.MergeNode.BranchingNode;
import rawit.processors.model.MergeNode.SharedNode;
import rawit.processors.model.MergeNode.TerminalNode;
import rawit.processors.model.MergeTree;
import rawit.processors.model.Parameter;

import javax.annotation.processing.Generated;
import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@code TypeSpec} for the Caller_Class (e.g. {@code Bar} for {@code @Curry} on
 * method {@code bar}, or {@code Constructor} for {@code @Constructor}).
 *
 * <p>The generated class:
 * <ul>
 *   <li>Is {@code public static} and annotated with {@code @Generated}.</li>
 *   <li>Implements the first stage interface.</li>
 *   <li>Contains nested stage interface {@code TypeSpec}s (via {@link StageInterfaceSpec} and
 *       {@link TerminalInterfaceSpec}).</li>
 *   <li>Uses a flat accumulator approach: per-stage inner classes ({@code Bar$WithX},
 *       {@code Bar$WithXY}, …) each holding {@code private final} fields for all accumulated
 *       arguments so far.</li>
 * </ul>
 */
public class CallerClassSpec {

    private static final AnnotationSpec GENERATED_ANNOTATION = AnnotationSpec
            .builder(Generated.class)
            .addMember("value", "$S", "rawit.processors.RawitAnnotationProcessor")
            .build();

    private final MergeTree tree;
    private final boolean isCurry;
    private final String callerClassName;

    public CallerClassSpec(final MergeTree tree) {
        this.tree = tree;
        // isCurry is true for @Curry annotations (including @Curry on constructors)
        // isCurry is false only for @Constructor annotations
        this.isCurry = tree.group().members().stream().anyMatch(m -> !m.isConstructorAnnotation());
        this.callerClassName = resolveCallerClassName();
    }

    /**
     * Builds and returns the Caller_Class {@link TypeSpec}.
     */
    public TypeSpec build() {
        final TypeSpec.Builder classBuilder = TypeSpec.classBuilder(callerClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addAnnotation(GENERATED_ANNOTATION);

        // Add enclosing instance field for instance methods
        final AnnotatedMethod representative = tree.group().members().get(0);
        if (!representative.isStatic() && !representative.isConstructor()) {
            final TypeName enclosingType = enclosingTypeName();
            classBuilder.addField(FieldSpec.builder(enclosingType, "__instance", Modifier.PRIVATE, Modifier.FINAL).build());
            classBuilder.addMethod(MethodSpec.constructorBuilder()
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(enclosingType, "instance")
                    .addStatement("this.__instance = instance")
                    .build());
        } else {
            classBuilder.addMethod(MethodSpec.constructorBuilder()
                    .addModifiers(Modifier.PUBLIC)
                    .build());
        }

        // Generate stage method implementations and accumulator inner classes
        final List<TypeSpec> accumulators = new ArrayList<>();
        buildStageImplementations(classBuilder, accumulators, tree.root(), List.of(), representative);
        for (final TypeSpec acc : accumulators) {
            classBuilder.addType(acc);
        }

        // Add nested stage interfaces (from StageInterfaceSpec and TerminalInterfaceSpec)
        final StageInterfaceSpec stageSpec = new StageInterfaceSpec(tree);
        for (final TypeSpec stageIface : stageSpec.buildAll()) {
            classBuilder.addType(stageIface);
        }
        // Add terminal interface
        classBuilder.addType(new TerminalInterfaceSpec(representative).build());

        return classBuilder.build();
    }

    // -------------------------------------------------------------------------
    // Stage method implementation generation
    // -------------------------------------------------------------------------

    /**
     * Recursively generates stage method implementations on the current class builder and
     * accumulator inner classes for each stage transition.
     *
     * @param classBuilder  the class being built (starts as the Caller_Class, then accumulators)
     * @param accumulators  collector for generated accumulator inner classes
     * @param node          current merge tree node
     * @param accumulated   parameters accumulated so far (for accumulator class fields)
     * @param representative a representative AnnotatedMethod for exception/return type info
     */
    private void buildStageImplementations(
            final TypeSpec.Builder classBuilder,
            final List<TypeSpec> accumulators,
            final MergeNode node,
            final List<Parameter> accumulated,
            final AnnotatedMethod representative
    ) {
        if (node == null) return;

        switch (node) {
            case SharedNode shared -> {
                // Generate the stage method on the current class
                final TypeName paramType = TerminalInterfaceSpec.descriptorToTypeName(shared.typeDescriptor());
                final TypeName returnType = nextStageTypeName(shared.next(), shared.paramName(), accumulated.size() + 1);

                // Build the accumulator class name: CallerClass$With<PascalParam1><PascalParam2>...
                final List<Parameter> nextAccumulated = append(accumulated, new Parameter(shared.paramName(), shared.typeDescriptor()));
                final String accClassName = accumulatorClassName(nextAccumulated);

                final MethodSpec.Builder stageMethod = MethodSpec.methodBuilder(shared.paramName())
                        .addModifiers(Modifier.PUBLIC)
                        .returns(returnType)
                        .addParameter(paramType, shared.paramName())
                        .addStatement("return new $L($L)", accClassName, buildAccumulatorArgs(accumulated, shared.paramName(), representative));

                // Add @Override only when the current class implements the stage interface
                // (i.e., when we're inside an accumulator class, not the top-level Caller_Class)
                if (!accumulated.isEmpty()) {
                    stageMethod.addAnnotation(Override.class);
                }

                addCheckedExceptions(stageMethod, representative);
                classBuilder.addMethod(stageMethod.build());

                // Build the accumulator class for the next stage (recursion is handled inside)
                final TypeSpec acc = buildAccumulatorClass(accClassName, nextAccumulated, shared.next(), representative);
                accumulators.add(acc);
            }
            case BranchingNode branching -> {
                for (final MergeNode.Branch branch : branching.branches()) {
                    final TypeName paramType = TerminalInterfaceSpec.descriptorToTypeName(branch.typeDescriptor());
                    final TypeName returnType = nextStageTypeName(branch.next(), branch.paramName(), accumulated.size() + 1);
                    final List<Parameter> nextAccumulated = append(accumulated, new Parameter(branch.paramName(), branch.typeDescriptor()));
                    final String accClassName = accumulatorClassName(nextAccumulated);

                    final MethodSpec.Builder stageMethod = MethodSpec.methodBuilder(branch.paramName())
                            .addModifiers(Modifier.PUBLIC)
                            .returns(returnType)
                            .addParameter(paramType, branch.paramName())
                            .addStatement("return new $L($L)", accClassName, buildAccumulatorArgs(accumulated, branch.paramName(), representative));

                    if (!accumulated.isEmpty()) {
                        stageMethod.addAnnotation(Override.class);
                    }

                    addCheckedExceptions(stageMethod, representative);
                    classBuilder.addMethod(stageMethod.build());

                    final TypeSpec acc = buildAccumulatorClass(accClassName, nextAccumulated, branch.next(), representative);
                    accumulators.add(acc);
                }
            }
            case TerminalNode terminal -> {
                // Generate invoke()/construct() method on the current class
                buildTerminalMethod(classBuilder, accumulated, terminal.overloads().get(0));

                // If there's a continuation, also generate the next-stage method
                if (terminal.continuation() != null) {
                    buildStageImplementations(classBuilder, accumulators, terminal.continuation(), accumulated, representative);
                }
            }
        }
    }

    private TypeSpec buildAccumulatorClass(
            final String className,
            final List<Parameter> accumulated,
            final MergeNode next,
            final AnnotatedMethod representative
    ) {
        final TypeSpec.Builder builder = TypeSpec.classBuilder(className)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);

        // Determine the superinterface(s) for this accumulator class
        if (next instanceof TerminalNode terminal && terminal.continuation() != null) {
            // Prefix overload: this accumulator implements the combined interface
            // (which extends InvokeStageCaller and adds the continuation's stage methods)
            final TypeName combinedType = nextStageTypeName(next,
                    accumulated.isEmpty() ? "" : accumulated.get(accumulated.size() - 1).name(),
                    accumulated.size());
            builder.addSuperinterface(combinedType);
        } else {
            final TypeName nextStageType = nextStageTypeName(next,
                    accumulated.isEmpty() ? "" : accumulated.get(accumulated.size() - 1).name(),
                    accumulated.size());
            builder.addSuperinterface(nextStageType);
        }

        // Add enclosing instance field for instance methods
        if (!representative.isStatic() && !representative.isConstructor()) {
            final TypeName enclosingType = enclosingTypeName();
            builder.addField(FieldSpec.builder(enclosingType, "__instance", Modifier.PRIVATE, Modifier.FINAL).build());
        }

        // Add fields for all accumulated parameters
        for (final Parameter p : accumulated) {
            final TypeName fieldType = TerminalInterfaceSpec.descriptorToTypeName(p.typeDescriptor());
            builder.addField(FieldSpec.builder(fieldType, p.name(), Modifier.PRIVATE, Modifier.FINAL).build());
        }

        // Constructor
        final MethodSpec.Builder ctor = MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE);
        final StringBuilder ctorBody = new StringBuilder();
        if (!representative.isStatic() && !representative.isConstructor()) {
            final TypeName enclosingType = enclosingTypeName();
            ctor.addParameter(enclosingType, "instance");
            ctorBody.append("this.__instance = instance;\n");
        }
        for (final Parameter p : accumulated) {
            final TypeName fieldType = TerminalInterfaceSpec.descriptorToTypeName(p.typeDescriptor());
            ctor.addParameter(fieldType, p.name());
            ctorBody.append("this.").append(p.name()).append(" = ").append(p.name()).append(";\n");
        }
        ctor.addCode(ctorBody.toString());
        builder.addMethod(ctor.build());

        // Stage method implementations
        final List<TypeSpec> innerAccumulators = new ArrayList<>();
        buildStageImplementations(builder, innerAccumulators, next, accumulated, representative);
        for (final TypeSpec inner : innerAccumulators) {
            builder.addType(inner);
        }

        return builder.build();
    }

    private void buildTerminalMethod(
            final TypeSpec.Builder classBuilder,
            final List<Parameter> accumulated,
            final AnnotatedMethod overload
    ) {
        final String terminalMethodName = isCurry ? "invoke" : "construct";
        final TypeName returnType;
        if (!isCurry) {
            // @Constructor: construct() returns the enclosing class instance
            returnType = enclosingTypeName();
        } else if (overload.isConstructor()) {
            // @Curry on a constructor: invoke() returns the enclosing class instance
            returnType = enclosingTypeName();
        } else {
            returnType = TerminalInterfaceSpec.descriptorToTypeName(overload.returnTypeDescriptor());
        }

        final MethodSpec.Builder mb = MethodSpec.methodBuilder(terminalMethodName)
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType);

        addCheckedExceptions(mb, overload);

        // Build the invocation statement
        final String argList = accumulated.stream()
                .map(Parameter::name)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        if (overload.isConstructor()) {
            final String enclosingSimple = binarySimpleName(overload.enclosingClassName());
            mb.addStatement("return new $L($L)", enclosingSimple, argList);
        } else if (overload.isStatic()) {
            final String enclosingSimple = binarySimpleName(overload.enclosingClassName());
            if ("V".equals(overload.returnTypeDescriptor())) {
                mb.addStatement("$L.$L($L)", enclosingSimple, overload.methodName(), argList);
            } else {
                mb.addStatement("return $L.$L($L)", enclosingSimple, overload.methodName(), argList);
            }
        } else {
            if ("V".equals(overload.returnTypeDescriptor())) {
                mb.addStatement("__instance.$L($L)", overload.methodName(), argList);
            } else {
                mb.addStatement("return __instance.$L($L)", overload.methodName(), argList);
            }
        }

        classBuilder.addMethod(mb.build());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String resolveCallerClassName() {
        if (!isCurry) return "Constructor";
        final String groupName = tree.group().groupName();
        if ("<init>".equals(groupName)) {
            // @Curry on a constructor: use the class name + "Curry" as the caller class name
            final String enclosing = tree.group().enclosingClassName();
            final int lastSlash = enclosing.lastIndexOf('/');
            final String simpleName = lastSlash < 0 ? enclosing : enclosing.substring(lastSlash + 1);
            return simpleName + "Curry";
        }
        return StageInterfaceSpec.toPascalCase(groupName);
    }

    private TypeName firstStageTypeName() {
        return new StageInterfaceSpec(tree).nextTypeName(tree.root(), "", 0);
    }

    private TypeName nextStageTypeName(final MergeNode node, final String prevParam, final int position) {
        return new StageInterfaceSpec(tree).nextTypeName(node, prevParam, position);
    }

    private TypeName terminalTypeName() {
        return com.squareup.javapoet.ClassName.bestGuess(
                isCurry ? "InvokeStageCaller" : "ConstructStageCaller");
    }

    private TypeName enclosingTypeName() {
        return TerminalInterfaceSpec.binaryNameToClassName(tree.group().enclosingClassName());
    }

    private String accumulatorClassName(final List<Parameter> accumulated) {
        final StringBuilder sb = new StringBuilder(callerClassName).append("$With");
        for (final Parameter p : accumulated) {
            sb.append(StageInterfaceSpec.toPascalCase(p.name()));
        }
        return sb.toString();
    }

    private String buildAccumulatorArgs(
            final List<Parameter> accumulated,
            final String newParam,
            final AnnotatedMethod representative
    ) {
        final List<String> args = new ArrayList<>();
        if (!representative.isStatic() && !representative.isConstructor()) {
            args.add("__instance");
        }
        for (final Parameter p : accumulated) {
            args.add(p.name());
        }
        args.add(newParam);
        return String.join(", ", args);
    }

    private void addCheckedExceptions(final MethodSpec.Builder mb, final AnnotatedMethod method) {
        for (final String ex : method.checkedExceptions()) {
            mb.addException(TerminalInterfaceSpec.binaryNameToClassName(ex));
        }
    }

    private static String binarySimpleName(final String binaryName) {
        final String dotName = binaryName.replace('/', '.');
        final int lastDot = dotName.lastIndexOf('.');
        final String simpleName = lastDot < 0 ? dotName : dotName.substring(lastDot + 1);
        // Replace '$' with '.' so nested types render as valid Java source (e.g. Outer.Inner)
        return simpleName.replace('$', '.');
    }

    private static <T> List<T> append(final List<T> list, final T item) {
        final List<T> result = new ArrayList<>(list);
        result.add(item);
        return result;
    }
}
