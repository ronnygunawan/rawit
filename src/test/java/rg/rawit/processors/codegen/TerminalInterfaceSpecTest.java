package rg.rawit.processors.codegen;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import org.junit.jupiter.api.Test;
import rg.rawit.processors.model.AnnotatedMethod;
import rg.rawit.processors.model.Parameter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TerminalInterfaceSpec}.
 *
 * <p>Assertions are made on the generated Java source string produced by JavaPoet —
 * no compilation or bytecode loading is required.
 */
class TerminalInterfaceSpecTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static AnnotatedMethod invokerMethod(String returnDescriptor, List<String> exceptions) {
        return new AnnotatedMethod("com/example/Foo", "bar", false, false,
                List.of(new Parameter("x", "I")), returnDescriptor, exceptions);
    }

    private static AnnotatedMethod constructorMethod(List<String> exceptions) {
        // Use isConstructorAnnotation=true to indicate @Constructor annotation
        return new AnnotatedMethod("com/example/Foo", "<init>", false, true, true,
                List.of(new Parameter("id", "I")), "V", exceptions, 0x0001);
    }

    private static String toSource(TypeSpec spec) {
        return JavaFile.builder("com.example", spec).build().toString();
    }

    // -------------------------------------------------------------------------
    // @Invoker — InvokeStageInvoker
    // -------------------------------------------------------------------------

    @Test
    void invoker_generatesInvokeStageInvoker() {
        TypeSpec spec = new TerminalInterfaceSpec(invokerMethod("I", List.of())).build();
        assertEquals("InvokeStageInvoker", spec.name);
    }

    @Test
    void invoker_annotatedWithFunctionalInterface() {
        TypeSpec spec = new TerminalInterfaceSpec(invokerMethod("I", List.of())).build();
        String source = toSource(spec);
        assertTrue(source.contains("@FunctionalInterface"), "must carry @FunctionalInterface");
    }

    @Test
    void invoker_invokeMethodReturnsCorrectPrimitiveType() {
        TypeSpec spec = new TerminalInterfaceSpec(invokerMethod("I", List.of())).build();
        String source = toSource(spec);
        assertTrue(source.contains("int invoke()"), "invoke() must return int for descriptor 'I'");
    }

    @Test
    void invoker_voidReturnType() {
        TypeSpec spec = new TerminalInterfaceSpec(invokerMethod("V", List.of())).build();
        String source = toSource(spec);
        assertTrue(source.contains("void invoke()"), "invoke() must return void for descriptor 'V'");
    }

    @Test
    void invoker_longReturnType() {
        TypeSpec spec = new TerminalInterfaceSpec(invokerMethod("J", List.of())).build();
        String source = toSource(spec);
        assertTrue(source.contains("long invoke()"), "invoke() must return long for descriptor 'J'");
    }

    @Test
    void invoker_objectReturnType() {
        TypeSpec spec = new TerminalInterfaceSpec(
                invokerMethod("Ljava/lang/String;", List.of())).build();
        String source = toSource(spec);
        assertTrue(source.contains("String invoke()"), "invoke() must return String");
    }

    @Test
    void invoker_checkedExceptionsPropagated() {
        TypeSpec spec = new TerminalInterfaceSpec(
                invokerMethod("V", List.of("java/io/IOException"))).build();
        String source = toSource(spec);
        assertTrue(source.contains("throws IOException"), "checked exceptions must appear in throws clause");
    }

    @Test
    void invoker_multipleCheckedExceptions() {
        TypeSpec spec = new TerminalInterfaceSpec(
                invokerMethod("V", List.of("java/io/IOException", "java/lang/Exception"))).build();
        String source = toSource(spec);
        assertTrue(source.contains("IOException"), "IOException must be in throws");
        assertTrue(source.contains("Exception"), "Exception must be in throws");
    }

    @Test
    void invoker_noCheckedExceptions_noThrowsClause() {
        TypeSpec spec = new TerminalInterfaceSpec(invokerMethod("V", List.of())).build();
        String source = toSource(spec);
        assertFalse(source.contains("throws"), "no throws clause when no checked exceptions");
    }

    // -------------------------------------------------------------------------
    // @Constructor — ConstructStageInvoker
    // -------------------------------------------------------------------------

    @Test
    void constructor_generatesConstructStageInvoker() {
        TypeSpec spec = new TerminalInterfaceSpec(constructorMethod(List.of())).build();
        assertEquals("ConstructStageInvoker", spec.name);
    }

    @Test
    void constructor_annotatedWithFunctionalInterface() {
        TypeSpec spec = new TerminalInterfaceSpec(constructorMethod(List.of())).build();
        String source = toSource(spec);
        assertTrue(source.contains("@FunctionalInterface"), "must carry @FunctionalInterface");
    }

    @Test
    void constructor_constructMethodReturnsEnclosingType() {
        TypeSpec spec = new TerminalInterfaceSpec(constructorMethod(List.of())).build();
        String source = toSource(spec);
        assertTrue(source.contains("Foo construct()"), "construct() must return the enclosing class type");
    }

    @Test
    void constructor_checkedExceptionsPropagated() {
        TypeSpec spec = new TerminalInterfaceSpec(
                constructorMethod(List.of("java/io/IOException"))).build();
        String source = toSource(spec);
        assertTrue(source.contains("throws IOException"), "checked exceptions must appear in throws clause");
    }
}
