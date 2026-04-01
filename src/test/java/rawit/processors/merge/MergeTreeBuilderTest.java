package rawit.processors.merge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rawit.processors.model.*;
import rawit.processors.model.MergeNode.*;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MergeTreeBuilderTest {

    private List<String> errors;
    private Messager messager;
    private MergeTreeBuilder builder;

    @BeforeEach
    void setUp() {
        errors = new ArrayList<>();
        messager = new Messager() {
            @Override
            public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
                if (kind == Diagnostic.Kind.ERROR) errors.add(msg.toString());
            }
            @Override
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) { printMessage(kind, msg); }
            @Override
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) { printMessage(kind, msg); }
            @Override
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a, AnnotationValue v) { printMessage(kind, msg); }
        };
        builder = new MergeTreeBuilder(messager);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static AnnotatedMethod method(String name, Parameter... params) {
        return new AnnotatedMethod("com/example/Foo", name, false, false,
                List.of(params), "V", List.of());
    }

    private static Parameter p(String name, String type) {
        return new Parameter(name, type);
    }

    private static OverloadGroup group(String name, AnnotatedMethod... members) {
        return new OverloadGroup("com/example/Foo", name, List.of(members));
    }

    // -------------------------------------------------------------------------
    // Single overload
    // -------------------------------------------------------------------------

    @Test
    void singleOverload_producesSharedNodes() {
        // bar(int x, int y)
        OverloadGroup g = group("bar", method("bar", p("x", "I"), p("y", "I")));
        MergeTree tree = builder.build(g);

        assertNotNull(tree);
        assertTrue(errors.isEmpty());

        // root → SharedNode(x) → SharedNode(y) → TerminalNode
        SharedNode root = assertInstanceOf(SharedNode.class, tree.root());
        assertEquals("x", root.paramName());
        assertEquals("I", root.typeDescriptor());

        SharedNode second = assertInstanceOf(SharedNode.class, root.next());
        assertEquals("y", second.paramName());

        TerminalNode terminal = assertInstanceOf(TerminalNode.class, second.next());
        assertEquals(1, terminal.overloads().size());
        assertNull(terminal.continuation());
    }

    // -------------------------------------------------------------------------
    // Shared prefix
    // -------------------------------------------------------------------------

    @Test
    void sharedPrefix_thenBranching() {
        // bar(int x, int y)  and  bar(int x, String name)
        OverloadGroup g = group("bar",
                method("bar", p("x", "I"), p("y", "I")),
                method("bar", p("x", "I"), p("name", "Ljava/lang/String;")));
        MergeTree tree = builder.build(g);

        assertNotNull(tree);
        assertTrue(errors.isEmpty());

        // root → SharedNode(x) → BranchingNode
        SharedNode root = assertInstanceOf(SharedNode.class, tree.root());
        assertEquals("x", root.paramName());

        BranchingNode branch = assertInstanceOf(BranchingNode.class, root.next());
        assertEquals(2, branch.branches().size());

        List<String> branchNames = branch.branches().stream().map(Branch::paramName).toList();
        assertTrue(branchNames.contains("y"));
        assertTrue(branchNames.contains("name"));
    }

    // -------------------------------------------------------------------------
    // Branching at first parameter
    // -------------------------------------------------------------------------

    @Test
    void branchingAtFirstParam() {
        // bar(int x, int y)  and  bar(String name, int z)
        OverloadGroup g = group("bar",
                method("bar", p("x", "I"), p("y", "I")),
                method("bar", p("name", "Ljava/lang/String;"), p("z", "I")));
        MergeTree tree = builder.build(g);

        assertNotNull(tree);
        assertTrue(errors.isEmpty());

        BranchingNode root = assertInstanceOf(BranchingNode.class, tree.root());
        assertEquals(2, root.branches().size());

        List<String> names = root.branches().stream().map(Branch::paramName).toList();
        assertTrue(names.contains("x"));
        assertTrue(names.contains("name"));
    }

    // -------------------------------------------------------------------------
    // Branching at second parameter
    // -------------------------------------------------------------------------

    @Test
    void branchingAtSecondParam() {
        // bar(int x, int y, int z)  and  bar(int x, String s, int z)
        OverloadGroup g = group("bar",
                method("bar", p("x", "I"), p("y", "I"), p("z", "I")),
                method("bar", p("x", "I"), p("s", "Ljava/lang/String;"), p("z", "I")));
        MergeTree tree = builder.build(g);

        assertNotNull(tree);
        assertTrue(errors.isEmpty());

        SharedNode root = assertInstanceOf(SharedNode.class, tree.root());
        assertEquals("x", root.paramName());

        BranchingNode branch = assertInstanceOf(BranchingNode.class, root.next());
        assertEquals(2, branch.branches().size());
    }

    // -------------------------------------------------------------------------
    // Prefix overload (shorter is prefix of longer)
    // -------------------------------------------------------------------------

    @Test
    void prefixOverload_terminalWithContinuation() {
        // bar(int x, int y)  and  bar(int x, int y, int z)
        OverloadGroup g = group("bar",
                method("bar", p("x", "I"), p("y", "I")),
                method("bar", p("x", "I"), p("y", "I"), p("z", "I")));
        MergeTree tree = builder.build(g);

        assertNotNull(tree);
        assertTrue(errors.isEmpty());

        // root → SharedNode(x) → SharedNode(y) → TerminalNode(continuation=SharedNode(z))
        SharedNode root = assertInstanceOf(SharedNode.class, tree.root());
        SharedNode second = assertInstanceOf(SharedNode.class, root.next());
        assertEquals("y", second.paramName());

        TerminalNode terminal = assertInstanceOf(TerminalNode.class, second.next());
        assertEquals(1, terminal.overloads().size());
        assertNotNull(terminal.continuation(), "prefix overload must have a continuation");

        SharedNode cont = assertInstanceOf(SharedNode.class, terminal.continuation());
        assertEquals("z", cont.paramName());
    }

    // -------------------------------------------------------------------------
    // Same-name-different-type conflict
    // -------------------------------------------------------------------------

    @Test
    void sameNameDifferentType_emitsErrorAndReturnsNull() {
        // bar(int x, int y)  and  bar(int x, String x)  — 'x' at pos 1 has two types
        OverloadGroup g = group("bar",
                method("bar", p("x", "I"), p("x", "I")),
                method("bar", p("x", "I"), p("x", "Ljava/lang/String;")));
        MergeTree tree = builder.build(g);

        assertNull(tree, "conflict should return null");
        assertFalse(errors.isEmpty(), "at least one ERROR should be emitted");
        assertTrue(errors.stream().anyMatch(e -> e.contains("conflicting types") && e.contains("'x'")));
    }

    // -------------------------------------------------------------------------
    // Single-param overload (terminal immediately after first shared node)
    // -------------------------------------------------------------------------

    @Test
    void singleParamOverload_terminalAtFirstPosition() {
        OverloadGroup g = group("bar", method("bar", p("x", "I")));
        MergeTree tree = builder.build(g);

        assertNotNull(tree);
        SharedNode root = assertInstanceOf(SharedNode.class, tree.root());
        TerminalNode terminal = assertInstanceOf(TerminalNode.class, root.next());
        assertEquals(1, terminal.overloads().size());
        assertNull(terminal.continuation());
    }
}
