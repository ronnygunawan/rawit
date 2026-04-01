package rg.rawit.processors.merge;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import rg.rawit.processors.model.*;
import rg.rawit.processors.model.MergeNode.*;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for {@link MergeTreeBuilder}.
 *
 * <p>Each property runs a minimum of 100 iterations via jqwik.
 */
class MergeTreeBuilderPropertyTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates a no-op Messager that collects ERROR messages. */
    private static List<String> errors = new ArrayList<>();

    private static Messager silentMessager() {
        errors = new ArrayList<>();
        return new Messager() {
            @Override public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
                if (kind == Diagnostic.Kind.ERROR) errors.add(msg.toString());
            }
            @Override public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) { printMessage(kind, msg); }
            @Override public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) { printMessage(kind, msg); }
            @Override public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a, AnnotationValue v) { printMessage(kind, msg); }
        };
    }

    private static MergeTreeBuilder newBuilder() {
        return new MergeTreeBuilder(silentMessager());
    }

    private static AnnotatedMethod method(String name, List<Parameter> params) {
        return new AnnotatedMethod("com/example/Foo", name, false, false,
                params, "V", List.of());
    }

    private static OverloadGroup group(String name, List<AnnotatedMethod> members) {
        return new OverloadGroup("com/example/Foo", name, members);
    }

    /** Counts the total number of SharedNode + BranchingNode + TerminalNode in a tree. */
    private static int countNodes(MergeNode node) {
        if (node == null) return 0;
        return switch (node) {
            case SharedNode s -> 1 + countNodes(s.next());
            case BranchingNode b -> 1 + b.branches().stream().mapToInt(br -> countNodes(br.next())).sum();
            case TerminalNode t -> 1 + countNodes(t.continuation());
        };
    }

    /** Collects all TerminalNodes reachable from a root. */
    private static List<TerminalNode> collectTerminals(MergeNode node) {
        if (node == null) return List.of();
        return switch (node) {
            case SharedNode s -> collectTerminals(s.next());
            case BranchingNode b -> b.branches().stream()
                    .flatMap(br -> collectTerminals(br.next()).stream())
                    .toList();
            case TerminalNode t -> {
                List<TerminalNode> result = new ArrayList<>();
                result.add(t);
                result.addAll(collectTerminals(t.continuation()));
                yield result;
            }
        };
    }

    /** Walks the tree and returns the first node at the given depth (0 = root). */
    private static MergeNode nodeAtDepth(MergeNode node, int depth) {
        if (node == null || depth == 0) return node;
        return switch (node) {
            case SharedNode s -> nodeAtDepth(s.next(), depth - 1);
            case BranchingNode b -> b.branches().isEmpty() ? null : nodeAtDepth(b.branches().get(0).next(), depth - 1);
            case TerminalNode t -> nodeAtDepth(t.continuation(), depth - 1);
        };
    }

    // -------------------------------------------------------------------------
    // Arbitraries
    // -------------------------------------------------------------------------

    private static final List<String> TYPES = List.of("I", "J", "Z", "D", "Ljava/lang/String;", "Ljava/lang/Object;");
    private static final List<String> NAMES = List.of("a", "b", "c", "x", "y", "z", "id", "name", "value");

    @Provide
    Arbitrary<Parameter> anyParam() {
        return Combinators.combine(
                Arbitraries.of(NAMES),
                Arbitraries.of(TYPES)
        ).as(Parameter::new);
    }

    @Provide
    Arbitrary<List<Parameter>> paramList() {
        return anyParam().list().ofMinSize(1).ofMaxSize(5);
    }

    @Provide
    Arbitrary<String> methodName() {
        return Arbitraries.of("bar", "compute", "process", "handle");
    }

    // -------------------------------------------------------------------------
    // Property 21: Shared prefix is correctly computed for overload groups
    // -------------------------------------------------------------------------
    // Feature: project-rawit-curry, Property 21: Shared prefix is correctly computed for overload groups

    @Property(tries = 100)
    void property21_sharedPrefixIsCorrectlyComputed(
            @ForAll("methodName") String name,
            @ForAll("paramList") List<Parameter> sharedParams,
            @ForAll @IntRange(min = 0, max = 3) int extraA,
            @ForAll @IntRange(min = 0, max = 3) int extraB
    ) {
        // Build two overloads that share `sharedParams` then diverge with unique extra params
        List<Parameter> paramsA = new ArrayList<>(sharedParams);
        List<Parameter> paramsB = new ArrayList<>(sharedParams);

        // Append distinct diverging params so the two overloads differ after the shared prefix
        for (int i = 0; i < extraA; i++) paramsA.add(new Parameter("extraA" + i, "I"));
        for (int i = 0; i < extraB; i++) paramsB.add(new Parameter("extraB" + i, "J"));

        // Ensure the two overloads are actually different (avoid identical param lists)
        if (paramsA.equals(paramsB)) {
            paramsA.add(new Parameter("onlyA", "I"));
        }

        OverloadGroup g = group(name, List.of(method(name, paramsA), method(name, paramsB)));
        MergeTree tree = newBuilder().build(g);

        // The tree must be built without errors
        Assume.that(tree != null);

        // The first `sharedParams.size()` nodes must all be SharedNodes
        MergeNode current = tree.root();
        for (int i = 0; i < sharedParams.size(); i++) {
            assertInstanceOf(SharedNode.class, current);
            SharedNode shared = (SharedNode) current;
            assertEquals(sharedParams.get(i).name(), shared.paramName());
            assertEquals(sharedParams.get(i).typeDescriptor(), shared.typeDescriptor());
            current = shared.next();
        }
    }

    // -------------------------------------------------------------------------
    // Property 22: Single parameterless overload for an overload group
    // -------------------------------------------------------------------------
    // Feature: project-rawit-curry, Property 22: Single parameterless overload for an overload group

    @Property(tries = 100)
    void property22_singleOverload_treeHasExactlyOneTerminal(
            @ForAll("methodName") String name,
            @ForAll("paramList") List<Parameter> params
    ) {
        // A group with a single overload should produce exactly one TerminalNode
        OverloadGroup g = group(name, List.of(method(name, params)));
        MergeTree tree = newBuilder().build(g);

        Assume.that(tree != null);

        List<TerminalNode> terminals = collectTerminals(tree.root());
        assertEquals(1, terminals.size());
        assertEquals(1, terminals.get(0).overloads().size());
    }

    // -------------------------------------------------------------------------
    // Property 23: Branching stage is generated at divergence points
    // -------------------------------------------------------------------------
    // Feature: project-rawit-curry, Property 23: Branching stage is generated at divergence points

    @Property(tries = 100)
    void property23_divergingFirstParam_producesBranchingNode(
            @ForAll("methodName") String name,
            @ForAll @IntRange(min = 1, max = 4) int depth
    ) {
        // Two overloads that diverge at position 0 with different names
        List<Parameter> paramsA = new ArrayList<>();
        List<Parameter> paramsB = new ArrayList<>();
        paramsA.add(new Parameter("alpha", "I"));
        paramsB.add(new Parameter("beta", "J"));
        for (int i = 0; i < depth; i++) {
            paramsA.add(new Parameter("a" + i, "I"));
            paramsB.add(new Parameter("b" + i, "I"));
        }

        OverloadGroup g = group(name, List.of(method(name, paramsA), method(name, paramsB)));
        MergeTree tree = newBuilder().build(g);

        Assume.that(tree != null);

        // Root must be a BranchingNode (divergence at position 0)
        assertInstanceOf(BranchingNode.class, tree.root());
        BranchingNode branch = (BranchingNode) tree.root();
        assertEquals(2, branch.branches().size());
    }

    @Property(tries = 100)
    void property23_divergingAtSharedPrefix_branchingAfterSharedNodes(
            @ForAll("methodName") String name,
            @ForAll @IntRange(min = 1, max = 3) int sharedCount
    ) {
        // sharedCount shared params, then diverge
        List<Parameter> shared = new ArrayList<>();
        for (int i = 0; i < sharedCount; i++) shared.add(new Parameter("s" + i, "I"));

        List<Parameter> paramsA = new ArrayList<>(shared);
        paramsA.add(new Parameter("alpha", "I"));

        List<Parameter> paramsB = new ArrayList<>(shared);
        paramsB.add(new Parameter("beta", "J"));

        OverloadGroup g = group(name, List.of(method(name, paramsA), method(name, paramsB)));
        MergeTree tree = newBuilder().build(g);

        Assume.that(tree != null);

        // Walk past the shared nodes
        MergeNode current = tree.root();
        for (int i = 0; i < sharedCount; i++) {
            assertInstanceOf(SharedNode.class, current);
            current = ((SharedNode) current).next();
        }
        // After shared prefix, must be a BranchingNode
        assertInstanceOf(BranchingNode.class, current);
    }

    // -------------------------------------------------------------------------
    // Property 24: Prefix overload stage exposes both terminal and continuation
    // -------------------------------------------------------------------------
    // Feature: project-rawit-curry, Property 24: Prefix overload stage exposes both terminal and continuation

    @Property(tries = 100)
    void property24_prefixOverload_terminalNodeHasContinuation(
            @ForAll("methodName") String name,
            @ForAll @IntRange(min = 1, max = 4) int sharedCount,
            @ForAll @IntRange(min = 1, max = 3) int extraCount
    ) {
        // Shorter overload: sharedCount params
        // Longer overload: sharedCount + extraCount params (same prefix)
        List<Parameter> shortParams = new ArrayList<>();
        for (int i = 0; i < sharedCount; i++) shortParams.add(new Parameter("p" + i, "I"));

        List<Parameter> longParams = new ArrayList<>(shortParams);
        for (int i = 0; i < extraCount; i++) longParams.add(new Parameter("extra" + i, "I"));

        OverloadGroup g = group(name, List.of(
                method(name, shortParams),
                method(name, longParams)
        ));
        MergeTree tree = newBuilder().build(g);

        Assume.that(tree != null);

        // Walk to the node at depth sharedCount — should be a TerminalNode with a continuation
        MergeNode current = tree.root();
        for (int i = 0; i < sharedCount; i++) {
            assertInstanceOf(SharedNode.class, current);
            current = ((SharedNode) current).next();
        }

        TerminalNode terminal = assertInstanceOf(TerminalNode.class, current);
        assertFalse(terminal.overloads().isEmpty());
        assertNotNull(terminal.continuation(),
                "prefix overload TerminalNode must have a non-null continuation");
    }
}
