package rawit.processors.codegen;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import rawit.processors.model.*;
import rawit.processors.model.MergeNode.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for {@link StageInterfaceSpec}.
 *
 * <p>Each property runs a minimum of 100 iterations via jqwik.
 */
class StageInterfaceSpecPropertyTest {

    // -------------------------------------------------------------------------
    // Arbitraries
    // -------------------------------------------------------------------------

    private static final List<String> TYPES = List.of("I", "J", "Z", "D", "Ljava/lang/String;");
    private static final List<String> NAMES = List.of("a", "b", "c", "x", "y", "z", "id", "name");

    @Provide
    Arbitrary<Parameter> anyParam() {
        return Combinators.combine(
                Arbitraries.of(NAMES),
                Arbitraries.of(TYPES)
        ).as(Parameter::new);
    }

    @Provide
    Arbitrary<List<Parameter>> paramList() {
        return anyParam().list().ofMinSize(1).ofMaxSize(5)
                .filter(params -> {
                    // Ensure unique names to avoid same-name-different-type issues
                    long distinct = params.stream().map(Parameter::name).distinct().count();
                    return distinct == params.size();
                });
    }

    @Provide
    Arbitrary<String> methodName() {
        return Arbitraries.of("bar", "compute", "process", "handle");
    }

    /** Builds a simple linear MergeTree from a single overload. */
    private static MergeTree linearTree(String methodName, List<Parameter> params) {
        AnnotatedMethod m = new AnnotatedMethod("com/example/Foo", methodName, false, false,
                params, "V", List.of());
        OverloadGroup g = new OverloadGroup("com/example/Foo", methodName, List.of(m));
        MergeNode root = buildChain(params, 0, m);
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

    private static String toPascalCase(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // -------------------------------------------------------------------------
    // Property 9: One Stage_Interface per parameter
    // Feature: curry-to-invoker-rename, Property 9: One Stage_Interface per parameter
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void property9_oneStageInterfacePerParameter(
            @ForAll("methodName") String name,
            @ForAll("paramList") List<Parameter> params
    ) {
        MergeTree tree = linearTree(name, params);
        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        assertEquals(params.size(), specs.size(),
                "must generate exactly one stage interface per parameter");
    }

    // -------------------------------------------------------------------------
    // Property 10: Each Stage_Interface declares exactly one method named after its parameter
    // Feature: curry-to-invoker-rename, Property 10: Each Stage_Interface declares exactly one method named after its parameter
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void property10_eachInterfaceHasExactlyOneMethodNamedAfterParam(
            @ForAll("methodName") String name,
            @ForAll("paramList") List<Parameter> params
    ) {
        MergeTree tree = linearTree(name, params);
        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        for (int i = 0; i < params.size(); i++) {
            TypeSpec iface = specs.get(i);
            String source = toSource(iface);
            String paramName = params.get(i).name();

            // Must contain a method named after the parameter
            assertTrue(source.contains(paramName + "("),
                    "interface for param '" + paramName + "' must declare a method named '" + paramName + "'");
        }
    }

    // -------------------------------------------------------------------------
    // Property 11: Chain structure — each stage method returns the correct next type
    // Feature: curry-to-invoker-rename, Property 11: Chain structure — each stage method returns the correct next type
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void property11_chainStructureIsCorrect(
            @ForAll("methodName") String name,
            @ForAll("paramList") List<Parameter> params
    ) {
        MergeTree tree = linearTree(name, params);
        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        for (int i = 0; i < params.size(); i++) {
            String source = toSource(specs.get(i));
            String paramName = params.get(i).name();

            if (i < params.size() - 1) {
                // Non-last stage: must return the next stage interface
                String nextIfaceName = toPascalCase(params.get(i + 1).name()) + "StageInvoker";
                assertTrue(source.contains(nextIfaceName + " " + paramName + "("),
                        "stage " + i + " method must return " + nextIfaceName);
            } else {
                // Last stage: must return InvokeStageInvoker
                assertTrue(source.contains("InvokeStageInvoker " + paramName + "("),
                        "last stage method must return InvokeStageInvoker");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 13: Stage interfaces carry @FunctionalInterface
    // Feature: curry-to-invoker-rename, Property 13: Stage interfaces carry @FunctionalInterface
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void property13_singleMethodStageInterfacesCarryFunctionalInterface(
            @ForAll("methodName") String name,
            @ForAll("paramList") List<Parameter> params
    ) {
        MergeTree tree = linearTree(name, params);
        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        // All interfaces in a linear (non-branching) tree have exactly one method
        for (TypeSpec iface : specs) {
            String source = toSource(iface);
            assertTrue(source.contains("@FunctionalInterface"),
                    "stage interface " + iface.name + " must carry @FunctionalInterface");
        }
    }

    // -------------------------------------------------------------------------
    // Additional: interface names follow PascalCase + StageInvoker convention
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void stageInterfaceNamesFollowConvention(
            @ForAll("methodName") String name,
            @ForAll("paramList") List<Parameter> params
    ) {
        MergeTree tree = linearTree(name, params);
        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        for (int i = 0; i < params.size(); i++) {
            String expectedName = toPascalCase(params.get(i).name()) + "StageInvoker";
            assertEquals(expectedName, specs.get(i).name,
                    "interface for param '" + params.get(i).name() + "' must be named " + expectedName);
        }
    }

    // -------------------------------------------------------------------------
    // Property 3: Stage interfaces use StageInvoker suffix
    // Feature: curry-to-invoker-rename, Property 3: Stage interfaces use StageInvoker suffix
    // Validates: Requirements 10.1, 10.8
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void property3_stageInterfacesUseStageInvokerSuffix(
            @ForAll("methodName") String name,
            @ForAll("paramList") List<Parameter> params
    ) {
        MergeTree tree = linearTree(name, params);
        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        for (TypeSpec iface : specs) {
            assertTrue(iface.name.endsWith("StageInvoker"),
                    "stage interface '" + iface.name + "' must end with 'StageInvoker', not 'StageCaller'");
            assertFalse(iface.name.endsWith("StageCaller"),
                    "stage interface '" + iface.name + "' must not end with 'StageCaller'");
        }
    }

    // -------------------------------------------------------------------------
    // Additional: primitive types are not boxed
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void primitiveTypesAreNotBoxed(
            @ForAll("methodName") String name,
            @ForAll @IntRange(min = 1, max = 3) int count
    ) {
        // Build a tree with only primitive int parameters
        List<Parameter> params = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            params.add(new Parameter("p" + i, "I"));
        }
        MergeTree tree = linearTree(name, params);
        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        for (int i = 0; i < count; i++) {
            String source = toSource(specs.get(i));
            assertTrue(source.contains("int p" + i),
                    "int parameter must not be boxed to Integer");
            assertFalse(source.contains("Integer p" + i),
                    "int parameter must not appear as Integer");
        }
    }
}
