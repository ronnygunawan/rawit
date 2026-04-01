package rawit.processors.codegen;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import org.junit.jupiter.api.Test;
import rawit.processors.model.*;
import rawit.processors.model.MergeNode.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CallerClassSpec}.
 *
 * <p>Assertions are made on the generated Java source string produced by JavaPoet.
 */
class CallerClassSpecTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static AnnotatedMethod instanceMethod(String name, String returnDesc, List<String> exceptions, Parameter... params) {
        return new AnnotatedMethod("com/example/Foo", name, false, false,
                List.of(params), returnDesc, exceptions);
    }

    private static AnnotatedMethod staticMethod(String name, String returnDesc, Parameter... params) {
        return new AnnotatedMethod("com/example/Foo", name, true, false,
                List.of(params), returnDesc, List.of());
    }

    private static AnnotatedMethod constructorMethod(Parameter... params) {
        return new AnnotatedMethod("com/example/Foo", "<init>", false, true, true,
                List.of(params), "V", List.of(), 0x0001);
    }

    private static Parameter p(String name, String type) {
        return new Parameter(name, type);
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

    // -------------------------------------------------------------------------
    // Class name tests
    // -------------------------------------------------------------------------

    @Test
    void callerClassNamedAfterMethodInPascalCase() {
        AnnotatedMethod m = instanceMethod("bar", "V", List.of(), p("x", "I"));
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();
        assertEquals("Bar", spec.name);
    }

    @Test
    void callerClassNamedConstructorForConstructorAnnotation() {
        AnnotatedMethod m = constructorMethod(p("id", "I"));
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();
        assertEquals("Constructor", spec.name);
    }

    // -------------------------------------------------------------------------
    // Modifiers and annotations
    // -------------------------------------------------------------------------

    @Test
    void callerClassIsPublicStatic() {
        AnnotatedMethod m = instanceMethod("bar", "V", List.of(), p("x", "I"));
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();
        String source = toSource(spec);
        assertTrue(source.contains("public static final class Bar"), "must be public static final");
    }

    @Test
    void callerClassCarriesGeneratedAnnotation() {
        AnnotatedMethod m = instanceMethod("bar", "V", List.of(), p("x", "I"));
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();
        String source = toSource(spec);
        assertTrue(source.contains("@Generated"), "must carry @Generated annotation");
        assertTrue(source.contains("rawit.processors.RawitAnnotationProcessor"),
                "must reference the processor class in @Generated");
    }

    // -------------------------------------------------------------------------
    // Fields — private final
    // -------------------------------------------------------------------------

    @Test
    void instanceMethod_hasPrivateFinalInstanceField() {
        AnnotatedMethod m = instanceMethod("bar", "V", List.of(), p("x", "I"));
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();
        String source = toSource(spec);
        assertTrue(source.contains("private final Foo __instance"), "must have private final __instance field");
    }

    @Test
    void staticMethod_noInstanceField() {
        AnnotatedMethod m = staticMethod("bar", "V", p("x", "I"));
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();
        String source = toSource(spec);
        assertFalse(source.contains("__instance"), "static method must not have __instance field");
    }

    @Test
    void accumulatorClass_hasPrivateFinalFields() {
        AnnotatedMethod m = instanceMethod("bar", "V", List.of(), p("x", "I"), p("y", "I"));
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();
        String source = toSource(spec);
        // The accumulator Bar$WithX should have private final int x
        assertTrue(source.contains("private final int x"), "accumulator must have private final int x");
    }

    // -------------------------------------------------------------------------
    // Implements clause
    // -------------------------------------------------------------------------

    @Test
    void callerClassImplementsFirstStageInterface() {
        AnnotatedMethod m = instanceMethod("bar", "V", List.of(), p("x", "I"), p("y", "I"));
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();
        String source = toSource(spec);
        // The Caller_Class no longer implements the first stage interface directly
        // (to avoid cyclic inheritance when written as a top-level class).
        // Instead, it exposes the first stage method directly.
        assertTrue(source.contains("public YStageCaller x(int x)"),
                "must have the first stage method x(int x) returning YStageCaller");
    }

    // -------------------------------------------------------------------------
    // Nested interfaces
    // -------------------------------------------------------------------------

    @Test
    void callerClassContainsNestedStageInterfaces() {
        AnnotatedMethod m = instanceMethod("bar", "V", List.of(), p("x", "I"), p("y", "I"));
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();
        String source = toSource(spec);
        assertTrue(source.contains("XStageCaller"), "must contain XStageCaller interface");
        assertTrue(source.contains("YStageCaller"), "must contain YStageCaller interface");
        assertTrue(source.contains("InvokeStageCaller"), "must contain InvokeStageCaller interface");
    }

    // -------------------------------------------------------------------------
    // invoke() body — instance method
    // -------------------------------------------------------------------------

    @Test
    void instanceMethod_invokeBodyDelegatesToCapturedInstance() {
        AnnotatedMethod m = instanceMethod("bar", "I", List.of(), p("x", "I"), p("y", "I"));
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();
        String source = toSource(spec);
        assertTrue(source.contains("__instance.bar(x, y)"), "invoke() must delegate to captured instance");
    }

    @Test
    void instanceMethod_voidReturn_invokeBodyNoReturn() {
        AnnotatedMethod m = instanceMethod("bar", "V", List.of(), p("x", "I"));
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();
        String source = toSource(spec);
        // void invoke() should call without return
        assertTrue(source.contains("__instance.bar(x)"), "void invoke() must call the method");
    }

    // -------------------------------------------------------------------------
    // invoke() body — static method
    // -------------------------------------------------------------------------

    @Test
    void staticMethod_invokeBodyDelegatesToStaticCall() {
        AnnotatedMethod m = staticMethod("bar", "I", p("x", "I"));
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();
        String source = toSource(spec);
        assertTrue(source.contains("Foo.bar(x)"), "invoke() must delegate to static method");
    }

    // -------------------------------------------------------------------------
    // construct() body — constructor
    // -------------------------------------------------------------------------

    @Test
    void constructor_constructBodyUsesNew() {
        AnnotatedMethod m = constructorMethod(p("id", "I"), p("name", "Ljava/lang/String;"));
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();
        String source = toSource(spec);
        assertTrue(source.contains("new Foo(id, name)"), "construct() must use new Foo(...)");
    }

    // -------------------------------------------------------------------------
    // Checked exceptions
    // -------------------------------------------------------------------------

    @Test
    void checkedExceptionsPropagatedToStageMethods() {
        AnnotatedMethod m = instanceMethod("bar", "V", List.of("java/io/IOException"), p("x", "I"));
        TypeSpec spec = new CallerClassSpec(linearTree(m)).build();
        String source = toSource(spec);
        assertTrue(source.contains("throws IOException"), "checked exceptions must appear in stage methods");
    }
}
