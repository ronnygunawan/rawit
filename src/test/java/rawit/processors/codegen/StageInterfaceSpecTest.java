package rawit.processors.codegen;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import org.junit.jupiter.api.Test;
import rawit.processors.model.*;
import rawit.processors.model.MergeNode.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StageInterfaceSpec}.
 *
 * <p>Assertions are made on the generated Java source string produced by JavaPoet.
 */
class StageInterfaceSpecTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static AnnotatedMethod method(String name, Parameter... params) {
        return new AnnotatedMethod("com/example/Foo", name, false, false,
                List.of(params), "V", List.of());
    }

    private static AnnotatedMethod methodWithExceptions(String name, List<String> exceptions, Parameter... params) {
        return new AnnotatedMethod("com/example/Foo", name, false, false,
                List.of(params), "V", exceptions);
    }

    private static Parameter p(String name, String type) {
        return new Parameter(name, type);
    }

    private static OverloadGroup group(String name, AnnotatedMethod... members) {
        return new OverloadGroup("com/example/Foo", name, List.of(members));
    }

    private static MergeTree singleOverloadTree(String methodName, Parameter... params) {
        AnnotatedMethod m = method(methodName, params);
        OverloadGroup g = group(methodName, m);
        // Build tree manually: SharedNode chain ending in TerminalNode
        MergeNode root = buildChain(List.of(params), 0, m);
        return new MergeTree(g, root);
    }

    private static MergeNode buildChain(List<Parameter> params, int pos, AnnotatedMethod m) {
        if (pos == params.size()) return new TerminalNode(List.of(m), null);
        return new SharedNode(params.get(pos).name(), params.get(pos).typeDescriptor(),
                buildChain(params, pos + 1, m));
    }

    private static String toSource(TypeSpec spec) {
        return JavaFile.builder("com.example", spec).build().toString();
    }

    // -------------------------------------------------------------------------
    // Interface name tests
    // -------------------------------------------------------------------------

    @Test
    void singleParam_interfaceNamedAfterParam() {
        MergeTree tree = singleOverloadTree("bar", p("x", "I"));
        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        assertEquals(1, specs.size());
        assertEquals("XStageCaller", specs.get(0).name);
    }

    @Test
    void twoParams_twoInterfacesWithCorrectNames() {
        MergeTree tree = singleOverloadTree("bar", p("x", "I"), p("y", "I"));
        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        assertEquals(2, specs.size());
        assertEquals("XStageCaller", specs.get(0).name);
        assertEquals("YStageCaller", specs.get(1).name);
    }

    @Test
    void branchingAtFirstParam_interfaceNamedAfterMethod() {
        // bar(int x) and bar(String name) — diverge at position 0
        AnnotatedMethod m1 = method("bar", p("x", "I"));
        AnnotatedMethod m2 = method("bar", p("name", "Ljava/lang/String;"));
        OverloadGroup g = group("bar", m1, m2);
        MergeNode root = new BranchingNode(List.of(
                new MergeNode.Branch("x", "I", new TerminalNode(List.of(m1), null)),
                new MergeNode.Branch("name", "Ljava/lang/String;", new TerminalNode(List.of(m2), null))
        ));
        MergeTree tree = new MergeTree(g, root);

        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        // First interface should be named BarStageCaller (method name at position 0)
        assertTrue(specs.stream().anyMatch(s -> s.name.equals("BarStageCaller")),
                "branching at position 0 must produce BarStageCaller");
    }

    @Test
    void branchingAtSecondParam_interfaceNamedAfterPrevParam() {
        // bar(int x, int y) and bar(int x, String name) — shared x, diverge at position 1
        AnnotatedMethod m1 = method("bar", p("x", "I"), p("y", "I"));
        AnnotatedMethod m2 = method("bar", p("x", "I"), p("name", "Ljava/lang/String;"));
        OverloadGroup g = group("bar", m1, m2);
        MergeNode root = new SharedNode("x", "I",
                new BranchingNode(List.of(
                        new MergeNode.Branch("y", "I", new TerminalNode(List.of(m1), null)),
                        new MergeNode.Branch("name", "Ljava/lang/String;", new TerminalNode(List.of(m2), null))
                )));
        MergeTree tree = new MergeTree(g, root);

        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        // XStageCaller for the shared x, then BarXStageCaller for the branching (prev param = x)
        assertTrue(specs.stream().anyMatch(s -> s.name.equals("XStageCaller")),
                "shared x must produce XStageCaller");
        assertTrue(specs.stream().anyMatch(s -> s.name.equals("BarXStageCaller")),
                "branching after x must produce BarXStageCaller");
    }

    // -------------------------------------------------------------------------
    // Method name and parameter type tests
    // -------------------------------------------------------------------------

    @Test
    void stageMethodNamedAfterParameter() {
        MergeTree tree = singleOverloadTree("bar", p("myParam", "I"));
        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        String source = toSource(specs.get(0));
        assertTrue(source.contains("myParam("), "stage method must be named after the parameter");
    }

    @Test
    void primitiveParamType_notBoxed() {
        MergeTree tree = singleOverloadTree("bar", p("x", "I"), p("y", "J"));
        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        String xSource = toSource(specs.get(0));
        assertTrue(xSource.contains("int x"), "int parameter must not be boxed");

        String ySource = toSource(specs.get(1));
        assertTrue(ySource.contains("long y"), "long parameter must not be boxed");
    }

    @Test
    void objectParamType_usedDirectly() {
        MergeTree tree = singleOverloadTree("bar", p("name", "Ljava/lang/String;"));
        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        String source = toSource(specs.get(0));
        assertTrue(source.contains("String name"), "String parameter must appear as String");
    }

    // -------------------------------------------------------------------------
    // Return type tests
    // -------------------------------------------------------------------------

    @Test
    void firstStageReturnsSecondStageInterface() {
        MergeTree tree = singleOverloadTree("bar", p("x", "I"), p("y", "I"));
        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        String xSource = toSource(specs.get(0));
        assertTrue(xSource.contains("YStageCaller x("), "x() must return YStageCaller");
    }

    @Test
    void lastStageReturnsInvokeStageCaller() {
        MergeTree tree = singleOverloadTree("bar", p("x", "I"), p("y", "I"));
        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        String ySource = toSource(specs.get(1));
        assertTrue(ySource.contains("InvokeStageCaller y("), "y() must return InvokeStageCaller");
    }

    @Test
    void singleParamStageReturnsInvokeStageCaller() {
        MergeTree tree = singleOverloadTree("bar", p("x", "I"));
        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        String source = toSource(specs.get(0));
        assertTrue(source.contains("InvokeStageCaller x("), "single-param stage must return InvokeStageCaller");
    }

    // -------------------------------------------------------------------------
    // @FunctionalInterface tests
    // -------------------------------------------------------------------------

    @Test
    void singleMethodInterface_annotatedWithFunctionalInterface() {
        MergeTree tree = singleOverloadTree("bar", p("x", "I"));
        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        String source = toSource(specs.get(0));
        assertTrue(source.contains("@FunctionalInterface"), "single-method interface must carry @FunctionalInterface");
    }

    // -------------------------------------------------------------------------
    // Checked exceptions propagation
    // -------------------------------------------------------------------------

    @Test
    void checkedExceptionsPropagatedToStageMethod() {
        AnnotatedMethod m = methodWithExceptions("bar", List.of("java/io/IOException"), p("x", "I"));
        OverloadGroup g = group("bar", m);
        MergeNode root = new SharedNode("x", "I", new TerminalNode(List.of(m), null));
        MergeTree tree = new MergeTree(g, root);

        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();
        String source = toSource(specs.get(0));
        assertTrue(source.contains("throws IOException"), "checked exceptions must be in stage method throws clause");
    }

    // -------------------------------------------------------------------------
    // @Constructor — StageConstructor naming
    // -------------------------------------------------------------------------

    @Test
    void constructor_interfaceNamedWithStageConstructorSuffix() {
        // Use isConstructorAnnotation=true to indicate @Constructor annotation
        AnnotatedMethod m = new AnnotatedMethod("com/example/Foo", "<init>", false, true, true,
                List.of(p("id", "I"), p("name", "Ljava/lang/String;")), "V", List.of(), 0x0001);
        OverloadGroup g = group("<init>", m);
        MergeNode root = new SharedNode("id", "I",
                new SharedNode("name", "Ljava/lang/String;",
                        new TerminalNode(List.of(m), null)));
        MergeTree tree = new MergeTree(g, root);

        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        assertEquals(2, specs.size());
        assertEquals("IdStageConstructor", specs.get(0).name);
        assertEquals("NameStageConstructor", specs.get(1).name);
    }
}
