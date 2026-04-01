package rawit.processors.codegen;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import net.jqwik.api.*;
import rawit.processors.model.AnnotatedMethod;
import rawit.processors.model.Parameter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for {@link TerminalInterfaceSpec}.
 *
 * <p>Each property runs a minimum of 100 iterations via jqwik.
 */
class TerminalInterfaceSpecPropertyTest {

    // -------------------------------------------------------------------------
    // Arbitraries
    // -------------------------------------------------------------------------

    private static final List<String> PRIMITIVE_DESCRIPTORS = List.of("I", "J", "F", "D", "Z", "B", "C", "S");
    private static final List<String> OBJECT_DESCRIPTORS = List.of(
            "Ljava/lang/String;", "Ljava/lang/Object;", "Ljava/util/List;");
    private static final List<String> ALL_RETURN_DESCRIPTORS;

    static {
        ALL_RETURN_DESCRIPTORS = new java.util.ArrayList<>();
        ALL_RETURN_DESCRIPTORS.add("V");
        ALL_RETURN_DESCRIPTORS.addAll(PRIMITIVE_DESCRIPTORS);
        ALL_RETURN_DESCRIPTORS.addAll(OBJECT_DESCRIPTORS);
    }

    @Provide
    Arbitrary<String> anyReturnDescriptor() {
        return Arbitraries.of(ALL_RETURN_DESCRIPTORS);
    }

    @Provide
    Arbitrary<List<String>> anyCheckedExceptions() {
        return Arbitraries.of(
                List.of(),
                List.of("java/io/IOException"),
                List.of("java/lang/Exception"),
                List.of("java/io/IOException", "java/lang/Exception")
        );
    }

    @Provide
    Arbitrary<AnnotatedMethod> anyCurryMethod() {
        return Combinators.combine(anyReturnDescriptor(), anyCheckedExceptions())
                .as((ret, exs) -> new AnnotatedMethod(
                        "com/example/Foo", "bar", false, false,
                        List.of(new Parameter("x", "I")), ret, exs));
    }

    @Provide
    Arbitrary<AnnotatedMethod> anyConstructorMethod() {
        return anyCheckedExceptions().map(exs -> new AnnotatedMethod(
                "com/example/Foo", "<init>", false, true,
                List.of(new Parameter("id", "I")), "V", exs));
    }

    private static String toSource(TypeSpec spec) {
        return JavaFile.builder("com.example", spec).build().toString();
    }

    // -------------------------------------------------------------------------
    // Property 12: Terminal interface is generated
    // Feature: project-rawit-curry, Property 12: Terminal interface is generated
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void property12_curryTerminalInterfaceIsGenerated(@ForAll("anyCurryMethod") AnnotatedMethod method) {
        TypeSpec spec = new TerminalInterfaceSpec(method).build();

        assertNotNull(spec, "terminal interface must be generated");
        assertEquals("InvokeStageCaller", spec.name, "must be named InvokeStageCaller for @Curry");

        String source = toSource(spec);
        assertTrue(source.contains("invoke()"), "must declare invoke() method");
    }

    @Property(tries = 100)
    void property12_constructorTerminalInterfaceIsGenerated(@ForAll("anyConstructorMethod") AnnotatedMethod method) {
        TypeSpec spec = new TerminalInterfaceSpec(method).build();

        assertNotNull(spec, "terminal interface must be generated");
        assertEquals("ConstructStageCaller", spec.name, "must be named ConstructStageCaller for @Constructor");

        String source = toSource(spec);
        assertTrue(source.contains("construct()"), "must declare construct() method");
    }

    // -------------------------------------------------------------------------
    // Property 13: Stage interfaces carry @FunctionalInterface
    // Feature: project-rawit-curry, Property 13: Stage interfaces carry @FunctionalInterface
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void property13_curryTerminalCarriesFunctionalInterface(@ForAll("anyCurryMethod") AnnotatedMethod method) {
        TypeSpec spec = new TerminalInterfaceSpec(method).build();
        String source = toSource(spec);
        assertTrue(source.contains("@FunctionalInterface"),
                "InvokeStageCaller must carry @FunctionalInterface");
    }

    @Property(tries = 100)
    void property13_constructorTerminalCarriesFunctionalInterface(@ForAll("anyConstructorMethod") AnnotatedMethod method) {
        TypeSpec spec = new TerminalInterfaceSpec(method).build();
        String source = toSource(spec);
        assertTrue(source.contains("@FunctionalInterface"),
                "ConstructStageCaller must carry @FunctionalInterface");
    }

    // -------------------------------------------------------------------------
    // Property 14: Checked exceptions are propagated through the chain
    // Feature: project-rawit-curry, Property 14: Checked exceptions are propagated through the chain
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void property14_checkedExceptionsPropagatedInInvoke(@ForAll("anyCurryMethod") AnnotatedMethod method) {
        TypeSpec spec = new TerminalInterfaceSpec(method).build();
        String source = toSource(spec);

        for (String ex : method.checkedExceptions()) {
            String simpleName = ex.contains("/") ? ex.substring(ex.lastIndexOf('/') + 1) : ex;
            assertTrue(source.contains(simpleName),
                    "checked exception " + simpleName + " must appear in generated source");
        }

        if (!method.checkedExceptions().isEmpty()) {
            assertTrue(source.contains("throws"), "throws clause must be present when exceptions declared");
        }
    }

    @Property(tries = 100)
    void property14_checkedExceptionsPropagatedInConstruct(@ForAll("anyConstructorMethod") AnnotatedMethod method) {
        TypeSpec spec = new TerminalInterfaceSpec(method).build();
        String source = toSource(spec);

        for (String ex : method.checkedExceptions()) {
            String simpleName = ex.contains("/") ? ex.substring(ex.lastIndexOf('/') + 1) : ex;
            assertTrue(source.contains(simpleName),
                    "checked exception " + simpleName + " must appear in generated source");
        }
    }

    // -------------------------------------------------------------------------
    // Additional: return type correctness
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void invokeReturnTypeMatchesDescriptor(@ForAll("anyCurryMethod") AnnotatedMethod method) {
        TypeSpec spec = new TerminalInterfaceSpec(method).build();
        String source = toSource(spec);

        String expectedType = switch (method.returnTypeDescriptor()) {
            case "V" -> "void";
            case "I" -> "int";
            case "J" -> "long";
            case "F" -> "float";
            case "D" -> "double";
            case "Z" -> "boolean";
            case "B" -> "byte";
            case "C" -> "char";
            case "S" -> "short";
            default -> {
                String d = method.returnTypeDescriptor();
                if (d.startsWith("L") && d.endsWith(";")) {
                    String bin = d.substring(1, d.length() - 1);
                    yield bin.contains("/") ? bin.substring(bin.lastIndexOf('/') + 1) : bin;
                }
                yield "Object";
            }
        };

        assertTrue(source.contains(expectedType + " invoke()"),
                "invoke() return type must match descriptor " + method.returnTypeDescriptor()
                        + ", expected: " + expectedType);
    }
}
