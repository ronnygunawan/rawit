package rawit.processors.codegen;

import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import rawit.processors.model.AnnotatedMethod;
import rawit.processors.model.MergeNode;
import rawit.processors.model.MergeNode.BranchingNode;
import rawit.processors.model.MergeNode.SharedNode;
import rawit.processors.model.MergeNode.TerminalNode;
import rawit.processors.model.MergeTree;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds {@link TypeSpec} instances for each stage interface in a curried chain.
 *
 * <p>Applies Algorithm 2 (stage interface name resolution) from the design:
 * <ul>
 *   <li>{@code SharedNode} → {@code <PascalParam>StageCaller} / {@code <PascalParam>StageConstructor}</li>
 *   <li>{@code BranchingNode} at position 0 → {@code <PascalMethod>StageCaller}</li>
 *   <li>{@code BranchingNode} at position n → {@code <PascalPrevParam>StageCaller}</li>
 *   <li>{@code TerminalNode} with continuation → exposes both terminal and continuation methods</li>
 * </ul>
 *
 * <p>Primitive parameter types are used directly (no boxing). Each interface is annotated with
 * {@code @FunctionalInterface}. Checked exceptions are propagated to every stage method.
 */
public class StageInterfaceSpec {

    private final MergeTree tree;
    private final boolean isCurry;

    public StageInterfaceSpec(final MergeTree tree) {
        this.tree = tree;
        // isCurry is true for @Curry annotations (including @Curry on constructors)
        // isCurry is false only for @Constructor annotations
        this.isCurry = tree.group().members().stream().anyMatch(m -> !m.isConstructorAnnotation());
    }

    /**
     * Builds all stage interface {@link TypeSpec}s for the merge tree, in chain order.
     *
     * @return ordered list of stage interface specs (does not include the terminal interface)
     */
    public List<TypeSpec> buildAll() {
        final List<TypeSpec> result = new ArrayList<>();
        buildInterfaces(tree.root(), null, 0, result);
        return result;
    }

    // -------------------------------------------------------------------------
    // Recursive interface builder
    // -------------------------------------------------------------------------

    private void buildInterfaces(
            final MergeNode node,
            final String prevParamName,
            final int position,
            final List<TypeSpec> out
    ) {
        if (node == null) return;

        switch (node) {
            case SharedNode shared -> {
                final String ifaceName = stageInterfaceName(shared.paramName(), prevParamName, position, false);
                final TypeName returnType = nextTypeName(shared.next(), shared.paramName(), position + 1);
                final TypeSpec iface = buildSingleMethodInterface(
                        ifaceName, shared.paramName(), shared.typeDescriptor(), returnType);
                out.add(iface);
                buildInterfaces(shared.next(), shared.paramName(), position + 1, out);
            }
            case BranchingNode branching -> {
                final String ifaceName = branchingInterfaceName(prevParamName, position);
                final TypeSpec iface = buildBranchingInterface(ifaceName, branching, position);
                out.add(iface);
                // Recurse into each branch
                for (final MergeNode.Branch branch : branching.branches()) {
                    buildInterfaces(branch.next(), branch.paramName(), position + 1, out);
                }
            }
            case TerminalNode terminal -> {
                // If there's a continuation, generate a combined interface that exposes both
                // invoke()/construct() and the next-stage method(s)
                if (terminal.continuation() != null) {
                    // Generate the combined interface
                    final String combinedIfaceName = combinedInterfaceName(prevParamName, position);
                    final TypeSpec combinedIface = buildCombinedInterface(
                            combinedIfaceName, terminal.continuation(), prevParamName, position);
                    out.add(combinedIface);
                    // Recurse into the continuation to generate its stage interfaces
                    buildInterfaces(terminal.continuation(), prevParamName, position, out);
                }
                // Terminal itself is handled by TerminalInterfaceSpec — no stage interface here
            }
        }
    }

    // -------------------------------------------------------------------------
    // Interface builders
    // -------------------------------------------------------------------------

    private TypeSpec buildSingleMethodInterface(
            final String ifaceName,
            final String paramName,
            final String typeDescriptor,
            final TypeName returnType
    ) {
        final MethodSpec method = buildStageMethod(paramName, typeDescriptor, returnType);
        return TypeSpec.interfaceBuilder(ifaceName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(FunctionalInterface.class)
                .addMethod(method)
                .build();
    }

    private TypeSpec buildBranchingInterface(
            final String ifaceName,
            final BranchingNode branching,
            final int position
    ) {
        final TypeSpec.Builder builder = TypeSpec.interfaceBuilder(ifaceName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(FunctionalInterface.class);

        for (final MergeNode.Branch branch : branching.branches()) {
            final TypeName returnType = nextTypeName(branch.next(), branch.paramName(), position + 1);
            builder.addMethod(buildStageMethod(branch.paramName(), branch.typeDescriptor(), returnType));
        }

        // A branching interface with more than one method cannot be @FunctionalInterface.
        // Remove the annotation if there are multiple methods.
        if (branching.branches().size() > 1) {
            return TypeSpec.interfaceBuilder(ifaceName)
                    .addModifiers(Modifier.PUBLIC)
                    .addMethods(builder.build().methodSpecs)
                    .build();
        }

        return builder.build();
    }

    private MethodSpec buildStageMethod(
            final String paramName,
            final String typeDescriptor,
            final TypeName returnType
    ) {
        final TypeName paramType = TerminalInterfaceSpec.descriptorToTypeName(typeDescriptor);
        final MethodSpec.Builder mb = MethodSpec.methodBuilder(paramName)
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(returnType)
                .addParameter(paramType, paramName);

        // Propagate checked exceptions from all members in the group — deduplicated
        final Set<String> seen = new LinkedHashSet<>();
        for (final AnnotatedMethod member : tree.group().members()) {
            seen.addAll(member.checkedExceptions());
        }
        for (final String ex : seen) {
            mb.addException(TerminalInterfaceSpec.binaryNameToClassName(ex));
        }

        return mb.build();
    }

    // -------------------------------------------------------------------------
    // Name resolution — Algorithm 2
    // -------------------------------------------------------------------------

    private String stageInterfaceName(
            final String paramName,
            final String prevParamName,
            final int position,
            final boolean isBranching
    ) {
        final String suffix = isCurry ? "StageCaller" : "StageConstructor";
        return toPascalCase(paramName) + suffix;
    }

    private String branchingInterfaceName(final String prevParamName, final int position) {
        final String suffix = isCurry ? "StageCaller" : "StageConstructor";
        if (position == 0) {
            // Divergence at first param → use method name only
            return toPascalCase(tree.group().groupName()) + suffix;
        }
        // Divergence after shared prefix → use method name + previous param name
        // e.g. for method "bar" diverging after param "x": "BarXStageCaller"
        return toPascalCase(tree.group().groupName()) + toPascalCase(prevParamName) + suffix;
    }

    // -------------------------------------------------------------------------
    // Return type resolution for a node
    // -------------------------------------------------------------------------

    /**
     * Determines the return type of a stage method that leads into {@code nextNode}.
     */
    TypeName nextTypeName(final MergeNode nextNode, final String currentParamName, final int nextPosition) {
        if (nextNode == null) {
            // Should not happen in a well-formed tree, but guard anyway
            return terminalTypeName();
        }
        return switch (nextNode) {
            case SharedNode shared -> {
                final String name = stageInterfaceName(shared.paramName(), currentParamName, nextPosition, false);
                yield com.squareup.javapoet.ClassName.bestGuess(name);
            }
            case BranchingNode branching -> {
                final String name = branchingInterfaceName(currentParamName, nextPosition);
                yield com.squareup.javapoet.ClassName.bestGuess(name);
            }
            case TerminalNode terminal -> {
                if (terminal.continuation() != null) {
                    // The terminal node also has a continuation — the return type is the
                    // combined interface that exposes both invoke() and the continuation's methods
                    yield com.squareup.javapoet.ClassName.bestGuess(
                            combinedInterfaceName(currentParamName, nextPosition));
                }
                yield terminalTypeName();
            }
        };
    }

    private TypeName terminalTypeName() {
        return com.squareup.javapoet.ClassName.bestGuess(
                isCurry ? "InvokeStageCaller" : "ConstructStageCaller");
    }

    /**
     * Returns the name of the combined interface for a {@link TerminalNode} with continuation.
     * The combined interface exposes both {@code invoke()}/{@code construct()} and the
     * continuation's stage methods.
     *
     * <p>Named {@code <PrevParam>WithInvokeStageCaller} (e.g. {@code YWithInvokeStageCaller}
     * for the stage after parameter {@code y}).
     */
    private String combinedInterfaceName(final String prevParamName, final int position) {
        final String suffix = isCurry ? "WithInvokeStageCaller" : "WithConstructStageCaller";
        if (prevParamName == null || prevParamName.isEmpty()) {
            return toPascalCase(tree.group().groupName()) + suffix;
        }
        return toPascalCase(prevParamName) + suffix;
    }

    /**
     * Builds the combined interface for a {@link TerminalNode} with continuation.
     * The combined interface extends the terminal interface and adds the continuation's
     * stage methods.
     */
    private TypeSpec buildCombinedInterface(
            final String ifaceName,
            final MergeNode continuation,
            final String prevParamName,
            final int position
    ) {
        final TypeSpec.Builder builder = TypeSpec.interfaceBuilder(ifaceName)
                .addModifiers(Modifier.PUBLIC);

        // Model the combined interface by extending the terminal interface
        builder.addSuperinterface(terminalTypeName());

        // Add the continuation's stage methods
        switch (continuation) {
            case SharedNode shared -> {
                final TypeName returnType = nextTypeName(shared.next(), shared.paramName(), position + 1);
                builder.addMethod(buildStageMethod(shared.paramName(), shared.typeDescriptor(), returnType));
            }
            case BranchingNode branching -> {
                for (final MergeNode.Branch branch : branching.branches()) {
                    final TypeName returnType = nextTypeName(branch.next(), branch.paramName(), position + 1);
                    builder.addMethod(buildStageMethod(branch.paramName(), branch.typeDescriptor(), returnType));
                }
            }
            case TerminalNode terminal -> {
                // Nested terminal — shouldn't happen in practice
            }
        }

        return builder.build();
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    static String toPascalCase(final String name) {
        if (name == null || name.isEmpty()) return name;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
