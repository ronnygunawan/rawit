package rawit.processors.codegen;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import org.junit.jupiter.api.Test;
import rawit.processors.model.*;
import rawit.processors.model.MergeNode.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for tag annotation propagation in generated stage interfaces.
 *
 * <p>Validates that {@link StageInterfaceSpec} correctly propagates tag annotation FQNs
 * from {@link MergeNode.SharedNode} onto the generated stage method parameters.
 *
 * <p>Validates: Requirements 10.3
 */
class StageInterfaceAnnotationPropagationTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static MergeNode buildChainWithAnnotations(List<Parameter> params, int pos, AnnotatedMethod m) {
        if (pos == params.size()) return new TerminalNode(List.of(m), null);
        return new SharedNode(
                params.get(pos).name(),
                params.get(pos).typeDescriptor(),
                buildChainWithAnnotations(params, pos + 1, m),
                params.get(pos).annotationFqns()
        );
    }

    private static String toSource(TypeSpec spec) {
        return JavaFile.builder("com.example", spec).build().toString();
    }

    // -------------------------------------------------------------------------
    // Test: tag annotations propagated to stage method parameters
    // -------------------------------------------------------------------------

    @Test
    void tagAnnotationsPropagatedToStageMethodParameters() {
        // @Constructor record User(@UserId long userId, @FirstName String firstName, @LastName String lastName)
        List<Parameter> params = List.of(
                new Parameter("userId", "J", List.of("com.example.UserId")),
                new Parameter("firstName", "Ljava/lang/String;", List.of("com.example.FirstName")),
                new Parameter("lastName", "Ljava/lang/String;", List.of("com.example.LastName"))
        );
        AnnotatedMethod m = new AnnotatedMethod("com/example/User", "<init>", false, true, true,
                params, "V", List.of(), 0x0001);
        OverloadGroup g = new OverloadGroup("com/example/User", "<init>", List.of(m));
        MergeNode root = buildChainWithAnnotations(params, 0, m);
        MergeTree tree = new MergeTree(g, root);

        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        assertEquals(3, specs.size(), "should generate 3 stage interfaces");

        // Stage 1: userId param should have @UserId
        String userIdSource = toSource(specs.get(0));
        assertTrue(userIdSource.contains("@UserId"), "userId param must carry @UserId annotation");
        assertTrue(userIdSource.contains("long userId"), "userId param must be long type");

        // Stage 2: firstName param should have @FirstName
        String firstNameSource = toSource(specs.get(1));
        assertTrue(firstNameSource.contains("@FirstName"), "firstName param must carry @FirstName annotation");
        assertTrue(firstNameSource.contains("String firstName"), "firstName param must be String type");

        // Stage 3: lastName param should have @LastName
        String lastNameSource = toSource(specs.get(2));
        assertTrue(lastNameSource.contains("@LastName"), "lastName param must carry @LastName annotation");
        assertTrue(lastNameSource.contains("String lastName"), "lastName param must be String type");
    }

    // -------------------------------------------------------------------------
    // Test: no tag annotations produces clean parameters
    // -------------------------------------------------------------------------

    @Test
    void noTagAnnotationsProducesCleanParameters() {
        List<Parameter> params = List.of(
                new Parameter("x", "I"),
                new Parameter("y", "Ljava/lang/String;")
        );
        AnnotatedMethod m = new AnnotatedMethod("com/example/Foo", "bar", false, false,
                params, "V", List.of());
        OverloadGroup g = new OverloadGroup("com/example/Foo", "bar", List.of(m));
        MergeNode root = buildChainWithAnnotations(params, 0, m);
        MergeTree tree = new MergeTree(g, root);

        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        assertEquals(2, specs.size(), "should generate 2 stage interfaces");

        // Stage 1: x param should have no annotations on the parameter
        String xSource = toSource(specs.get(0));
        assertTrue(xSource.contains("int x"), "x param must be int type");
        // The method signature should not have any annotation on the parameter itself
        assertTrue(xSource.contains("YStageInvoker x(int x)"),
                "x method signature should be clean without param annotations");

        // Stage 2: y param should have no annotations on the parameter
        String ySource = toSource(specs.get(1));
        assertTrue(ySource.contains("String y"), "y param must be String type");
        assertTrue(ySource.contains("InvokeStageInvoker y(String y)"),
                "y method signature should be clean without param annotations");
    }

    // -------------------------------------------------------------------------
    // Test: multiple tag annotations propagated to stage method parameters
    // -------------------------------------------------------------------------

    @Test
    void multipleTagAnnotationsPropagatedToStageMethodParameters() {
        // A parameter with two tag annotations should propagate both
        List<Parameter> params = List.of(
                new Parameter("value", "Ljava/lang/String;",
                        List.of("com.example.FirstName", "com.example.DisplayName"))
        );
        AnnotatedMethod m = new AnnotatedMethod("com/example/Foo", "bar", false, false,
                params, "V", List.of());
        OverloadGroup g = new OverloadGroup("com/example/Foo", "bar", List.of(m));
        MergeNode root = buildChainWithAnnotations(params, 0, m);
        MergeTree tree = new MergeTree(g, root);

        List<TypeSpec> specs = new StageInterfaceSpec(tree).buildAll();

        assertEquals(1, specs.size(), "should generate 1 stage interface");

        String source = toSource(specs.get(0));
        assertTrue(source.contains("@FirstName"), "value param must carry @FirstName annotation");
        assertTrue(source.contains("@DisplayName"), "value param must carry @DisplayName annotation");
        assertTrue(source.contains("String value"), "value param must be String type");
    }
}
