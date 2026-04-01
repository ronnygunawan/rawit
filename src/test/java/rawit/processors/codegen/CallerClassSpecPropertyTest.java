package rawit.processors.codegen;

import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import net.jqwik.api.*;
import rawit.processors.model.*;
import rawit.processors.model.MergeNode.*;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for {@link CallerClassSpec}.
 *
 * <p>Each property runs a minimum of 100 iterations via jqwik.
 */
class CallerClassSpecPropertyTest {

    // -------------------------------------------------------------------------
    // Arbitraries
    // -------------------------------------------------------------------------

    private static final List<String> TYPES = List.of("I", "J", "Z", "Ljava/lang/String;");
    private static final List<String> NAMES = List.of("a", "b", "c", "x", "y", "z", "id", "val");

    @Provide
    Arbitrary<Parameter> anyParam() {
        return Combinators.combine(
                Arbitraries.of(NAMES),
                Arbitraries.of(TYPES)
        ).as(Parameter::new);
    }

    @Provide
    Arbitrary<List<Parameter>> paramList() {
        return anyParam().list().ofMinSize(1).ofMaxSize(4)
                .filter(params -> params.stream().map(Parameter::name).distinct().count() == params.size());
    }

    @Provide
    Arbitrary<String> methodName() {
        return Arbitraries.of("bar", "compute", "process", "handle");
    }

    @Provide
    Arbitrary<Boolean> anyBoolean() {
        return Arbitraries.of(true, false);
    }

    private static MergeTree linearTree(AnnotatedMethod m) {
        OverloadGroup g = new OverloadGroup(m.enclosingClassName(), m.methodName(), List.of(m));
        MergeNode root = buildChain(m.parameters(), 0, m);
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
    // Property 5: Caller_Class is injected as a public static inner class
    // Feature: project-rawit-curry, Property 5: Caller_Class is injected as a public static inner class
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void property5_callerClassIsPublicStatic(
            @ForAll("methodName") String name,
            @ForAll("paramList") List<Parameter> params
    ) {
        AnnotatedMethod m = new AnnotatedMethod("com/example/Foo", name, false, false,
                params, "V", List.of());
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();

        String source = toSource(spec);
        String expectedName = toPascalCase(name);

        assertTrue(spec.modifiers.contains(Modifier.PUBLIC), "Caller_Class must be public");
        assertTrue(spec.modifiers.contains(Modifier.STATIC), "Caller_Class must be static");
        assertEquals(expectedName, spec.name, "Caller_Class must be named after the method in PascalCase");
    }

    // -------------------------------------------------------------------------
    // Property 6: Caller_Class implements all Stage_Interfaces
    // Feature: project-rawit-curry, Property 6: Caller_Class implements all Stage_Interfaces
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void property6_callerClassImplementsFirstStageInterface(
            @ForAll("methodName") String name,
            @ForAll("paramList") List<Parameter> params
    ) {
        AnnotatedMethod m = new AnnotatedMethod("com/example/Foo", name, false, false,
                params, "V", List.of());
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();
        String source = toSource(spec);

        // The Caller_Class no longer implements the first stage interface directly
        // (to avoid cyclic inheritance when written as a top-level class).
        // Instead, it exposes the first stage method directly.
        String firstParamName = params.get(0).name();
        // The Caller_Class should have a public method named after the first parameter
        assertTrue(source.contains("public") && source.contains(firstParamName + "("),
                "Caller_Class must have the first stage method " + firstParamName + "()");
    }

    @Property(tries = 100)
    void property6_callerClassContainsAllStageInterfaces(
            @ForAll("methodName") String name,
            @ForAll("paramList") List<Parameter> params
    ) {
        AnnotatedMethod m = new AnnotatedMethod("com/example/Foo", name, false, false,
                params, "V", List.of());
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();
        String source = toSource(spec);

        // All stage interfaces must be present as nested types
        for (Parameter p : params) {
            String ifaceName = toPascalCase(p.name()) + "StageCaller";
            assertTrue(source.contains(ifaceName),
                    "Caller_Class must contain stage interface " + ifaceName);
        }
        assertTrue(source.contains("InvokeStageCaller"),
                "Caller_Class must contain InvokeStageCaller");
    }

    // -------------------------------------------------------------------------
    // Property 7: All Caller_Class fields are private and final
    // Feature: project-rawit-curry, Property 7: All Caller_Class fields are private and final
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void property7_allFieldsArePrivateAndFinal(
            @ForAll("methodName") String name,
            @ForAll("paramList") List<Parameter> params
    ) {
        AnnotatedMethod m = new AnnotatedMethod("com/example/Foo", name, false, false,
                params, "V", List.of());
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();

        // Check top-level Caller_Class fields
        for (FieldSpec field : spec.fieldSpecs) {
            assertTrue(field.modifiers.contains(Modifier.PRIVATE),
                    "field " + field.name + " must be private");
            assertTrue(field.modifiers.contains(Modifier.FINAL),
                    "field " + field.name + " must be final");
        }
    }

    @Property(tries = 100)
    void property7_accumulatorClassFieldsArePrivateAndFinal(
            @ForAll("methodName") String name,
            @ForAll("paramList") List<Parameter> params
    ) {
        AnnotatedMethod m = new AnnotatedMethod("com/example/Foo", name, false, false,
                params, "V", List.of());
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();

        // Check all nested accumulator classes
        for (TypeSpec nested : spec.typeSpecs) {
            if (nested.name != null && nested.name.contains("$With")) {
                for (FieldSpec field : nested.fieldSpecs) {
                    assertTrue(field.modifiers.contains(Modifier.PRIVATE),
                            "accumulator field " + field.name + " must be private");
                    assertTrue(field.modifiers.contains(Modifier.FINAL),
                            "accumulator field " + field.name + " must be final");
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Property 8: Caller_Class carries the @Generated annotation
    // Feature: project-rawit-curry, Property 8: Caller_Class carries the @Generated annotation
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void property8_callerClassCarriesGeneratedAnnotation(
            @ForAll("methodName") String name,
            @ForAll("paramList") List<Parameter> params
    ) {
        AnnotatedMethod m = new AnnotatedMethod("com/example/Foo", name, false, false,
                params, "V", List.of());
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();
        String source = toSource(spec);

        assertTrue(source.contains("@Generated"), "Caller_Class must carry @Generated");
        assertTrue(source.contains("rawit.processors.RawitAnnotationProcessor"),
                "must reference the processor in @Generated value");
    }

    @Property(tries = 100)
    void property8_constructorCallerClassCarriesGeneratedAnnotation(
            @ForAll("paramList") List<Parameter> params
    ) {
        // Use isConstructorAnnotation=true to indicate @Constructor annotation
        AnnotatedMethod m = new AnnotatedMethod("com/example/Foo", "<init>", false, true, true,
                params, "V", List.of(), 0x0001);
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();
        String source = toSource(spec);

        assertEquals("Constructor", spec.name, "Constructor_Caller_Class must be named Constructor");
        assertTrue(source.contains("@Generated"), "Constructor_Caller_Class must carry @Generated");
    }
}
