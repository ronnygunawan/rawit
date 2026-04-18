package rawit.processors.codegen;

import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeSpec;
import net.jqwik.api.*;
import rawit.processors.model.*;
import rawit.processors.model.MergeNode.*;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for tag annotation propagation in generated stage interfaces.
 *
 * <p>Validates that {@link StageInterfaceSpec} correctly propagates tag annotation FQNs
 * from {@link MergeNode.SharedNode} onto the generated stage method parameters.
 */
class StageInterfaceAnnotationPropagationPropertyTest {

    // -------------------------------------------------------------------------
    // Arbitraries
    // -------------------------------------------------------------------------

    private static final List<String> TYPES = List.of("I", "J", "Z", "D", "Ljava/lang/String;");
    private static final List<String> PARAM_NAMES = List.of("a", "b", "c", "x", "y", "z", "id", "name");
    private static final List<String> TAG_FQNS = List.of(
            "com.example.UserId", "com.example.FirstName", "com.example.LastName",
            "com.example.Email", "com.example.PhoneNumber", "com.example.Address",
            "com.example.Age", "com.example.Currency"
    );

    /** A parameter that carries a random (possibly empty) list of tag annotation FQNs. */
    @Provide
    Arbitrary<Parameter> annotatedParam() {
        Arbitrary<String> names = Arbitraries.of(PARAM_NAMES);
        Arbitrary<String> types = Arbitraries.of(TYPES);
        Arbitrary<List<String>> fqns = Arbitraries.of(TAG_FQNS)
                .list().ofMinSize(0).ofMaxSize(3)
                .map(list -> list.stream().distinct().collect(Collectors.toList()));
        return Combinators.combine(names, types, fqns).as(Parameter::new);
    }

    /** List of parameters with unique names, each carrying random annotation FQNs. */
    @Provide
    Arbitrary<List<Parameter>> paramListWithAnnotations() {
        return annotatedParam().list().ofMinSize(1).ofMaxSize(5)
                .filter(params -> {
                    long distinct = params.stream().map(Parameter::name).distinct().count();
                    return distinct == params.size();
                });
    }

    /** List of parameters with unique names and NO annotation FQNs. */
    @Provide
    Arbitrary<List<Parameter>> paramListWithoutAnnotations() {
        Arbitrary<Parameter> plainParam = Combinators.combine(
                Arbitraries.of(PARAM_NAMES),
                Arbitraries.of(TYPES)
        ).as(Parameter::new);
        return plainParam.list().ofMinSize(1).ofMaxSize(5)
                .filter(params -> {
                    long distinct = params.stream().map(Parameter::name).distinct().count();
                    return distinct == params.size();
                });
    }

    @Provide
    Arbitrary<String> methodName() {
        return Arbitraries.of("bar", "compute", "process", "handle");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Builds a linear MergeTree from parameters, propagating annotationFqns into SharedNodes. */
    private static MergeTree linearTree(String methodName, List<Parameter> params) {
        AnnotatedMethod m = new AnnotatedMethod("com/example/Foo", methodName, false, false,
                params, "V", List.of());
        OverloadGroup g = new OverloadGroup("com/example/Foo", methodName, List.of(m));
        MergeNode root = buildChain(params, 0, m);
        return new MergeTree(g, root);
    }

    private static MergeNode buildChain(List<Parameter> params, int pos, AnnotatedMethod m) {
        if (pos == params.size()) return new TerminalNode(List.of(m), null);
        return new SharedNode(
                params.get(pos).name(),
                params.get(pos).typeDescriptor(),
                buildChain(params, pos + 1, m),
                params.get(pos).annotationFqns()
        );
    }

    // -------------------------------------------------------------------------
    // Property 10: Generated stage method parameters carry propagated tag annotations
    // Feature: tagged-value-annotation, Property 10: Generated stage method parameters carry propagated tag annotations
    // Validates: Requirements 10.3
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void property10_generatedStageMethodParametersCarryPropagatedTagAnnotations(
            @ForAll("methodName") String name,
            @ForAll("paramListWithAnnotations") List<Parameter> params
    ) {
        MergeTree tree = linearTree(name, params);
        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        assertEquals(params.size(), specs.size(),
                "must generate exactly one stage interface per parameter");

        for (int i = 0; i < params.size(); i++) {
            Parameter originalParam = params.get(i);
            TypeSpec stageInterface = specs.get(i);

            // Each stage interface has exactly one method
            assertEquals(1, stageInterface.methodSpecs.size(),
                    "stage interface should have exactly one method");

            MethodSpec method = stageInterface.methodSpecs.get(0);
            assertEquals(1, method.parameters.size(),
                    "stage method should have exactly one parameter");

            ParameterSpec paramSpec = method.parameters.get(0);
            List<String> expectedFqns = originalParam.annotationFqns();
            List<String> actualFqns = paramSpec.annotations.stream()
                    .map(a -> a.type.toString())
                    .collect(Collectors.toList());

            assertEquals(expectedFqns.size(), actualFqns.size(),
                    "annotation count mismatch for param '" + originalParam.name()
                            + "': expected " + expectedFqns + " but got " + actualFqns);

            for (String expectedFqn : expectedFqns) {
                assertTrue(actualFqns.contains(expectedFqn),
                        "expected annotation @" + expectedFqn + " on param '"
                                + originalParam.name() + "' but found " + actualFqns);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 10 (complement): Parameters without tag annotations produce no annotation specs
    // Feature: tagged-value-annotation, Property 10: Parameters without tag annotations produce no annotation specs
    // Validates: Requirements 10.3
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void property10_parametersWithoutAnnotationsProduceNoAnnotationSpecs(
            @ForAll("methodName") String name,
            @ForAll("paramListWithoutAnnotations") List<Parameter> params
    ) {
        MergeTree tree = linearTree(name, params);
        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        assertEquals(params.size(), specs.size(),
                "must generate exactly one stage interface per parameter");

        for (int i = 0; i < params.size(); i++) {
            TypeSpec stageInterface = specs.get(i);
            MethodSpec method = stageInterface.methodSpecs.get(0);
            ParameterSpec paramSpec = method.parameters.get(0);

            assertTrue(paramSpec.annotations.isEmpty(),
                    "param '" + params.get(i).name() + "' has no tag annotations, "
                            + "so generated parameter should have no annotation specs, "
                            + "but found: " + paramSpec.annotations);
        }
    }
}
