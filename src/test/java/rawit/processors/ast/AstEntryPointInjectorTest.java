package rawit.processors.ast;

import org.junit.jupiter.api.Test;
import rawit.processors.model.AnnotatedMethod;
import rawit.processors.model.MergeNode;
import rawit.processors.model.MergeNode.TerminalNode;
import rawit.processors.model.MergeNode.SharedNode;
import rawit.processors.model.MergeTree;
import rawit.processors.model.OverloadGroup;
import rawit.processors.model.Parameter;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.*;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AstEntryPointInjector}.
 *
 * <p>Tests focus on the static helper methods that can be exercised without a live
 * javac context, plus a smoke-test that {@link AstEntryPointInjector#tryCreate} succeeds
 * (returns non-null) when running under javac.
 *
 * <p>Validates: entry-point name resolution, caller-FQN resolution, and graceful
 * non-null creation under javac.
 */
class AstEntryPointInjectorTest {

    // =========================================================================
    // Helpers
    // =========================================================================

    private static MergeTree invokerTree(final String enclosing, final String methodName,
                                          final boolean isStatic, final Parameter... params) {
        final AnnotatedMethod m = new AnnotatedMethod(
                enclosing, methodName, isStatic, false, false,
                List.of(params), "V", List.of(), 0x0001);
        final OverloadGroup group = new OverloadGroup(enclosing, methodName, List.of(m));
        final MergeNode root = buildChain(List.of(params), 0, m);
        return new MergeTree(group, root);
    }

    private static MergeTree constructorAnnotTree(final String enclosing,
                                                   final Parameter... params) {
        final AnnotatedMethod m = new AnnotatedMethod(
                enclosing, "<init>", false, true, true,
                List.of(params), "V", List.of(), 0x0001);
        final OverloadGroup group = new OverloadGroup(enclosing, "<init>", List.of(m));
        final MergeNode root = buildChain(List.of(params), 0, m);
        return new MergeTree(group, root);
    }

    private static MergeTree invokerOnConstructorTree(final String enclosing,
                                                       final Parameter... params) {
        final AnnotatedMethod m = new AnnotatedMethod(
                enclosing, "<init>", false, true, false,
                List.of(params), "V", List.of(), 0x0001);
        final OverloadGroup group = new OverloadGroup(enclosing, "<init>", List.of(m));
        final MergeNode root = buildChain(List.of(params), 0, m);
        return new MergeTree(group, root);
    }

    private static MergeNode buildChain(final List<Parameter> params, final int pos,
                                         final AnnotatedMethod m) {
        if (pos == params.size()) return new TerminalNode(List.of(m), null);
        return new SharedNode(params.get(pos).name(), params.get(pos).typeDescriptor(),
                buildChain(params, pos + 1, m));
    }

    // =========================================================================
    // resolveEntryPointName
    // =========================================================================

    @Test
    void entryPointName_instanceInvoker_usesGroupName() {
        final MergeTree tree = invokerTree("com/example/Calculator", "add", false,
                new Parameter("x", "I"));
        final String name = AstEntryPointInjector.resolveEntryPointName(tree, false, false);
        assertEquals("add", name);
    }

    @Test
    void entryPointName_staticInvoker_usesGroupName() {
        final MergeTree tree = invokerTree("com/example/Calculator", "multiply", true,
                new Parameter("a", "I"));
        final String name = AstEntryPointInjector.resolveEntryPointName(tree, false, false);
        assertEquals("multiply", name);
    }

    @Test
    void entryPointName_constructorAnnotation_returnsConstructor() {
        final MergeTree tree = constructorAnnotTree("com/example/Point",
                new Parameter("x", "I"), new Parameter("y", "I"));
        final String name = AstEntryPointInjector.resolveEntryPointName(tree, true, false);
        assertEquals("constructor", name);
    }

    @Test
    void entryPointName_invokerOnConstructor_returnsLowercaseSimpleName() {
        final MergeTree tree = invokerOnConstructorTree("com/example/Point",
                new Parameter("x", "I"));
        final String name = AstEntryPointInjector.resolveEntryPointName(tree, false, true);
        assertEquals("point", name);
    }

    @Test
    void entryPointName_invokerOnConstructor_defaultPackage_returnsLowercaseSimpleName() {
        final MergeTree tree = invokerOnConstructorTree("Widget",
                new Parameter("id", "I"));
        final String name = AstEntryPointInjector.resolveEntryPointName(tree, false, true);
        assertEquals("widget", name);
    }

    // =========================================================================
    // resolveCallerFqn
    // =========================================================================

    @Test
    void callerFqn_instanceInvoker_hasGeneratedSubpackageAndInvokerSuffix() {
        final MergeTree tree = invokerTree("com/example/Calculator", "add", false,
                new Parameter("x", "I"));
        final String fqn = AstEntryPointInjector.resolveCallerFqn(tree, false);
        assertEquals("com.example.generated.CalculatorAddInvoker", fqn);
    }

    @Test
    void callerFqn_staticInvoker_multiWord_hasPascalCaseSuffix() {
        final MergeTree tree = invokerTree("com/example/model/Calculator", "multiply", true,
                new Parameter("a", "I"));
        final String fqn = AstEntryPointInjector.resolveCallerFqn(tree, false);
        assertEquals("com.example.model.generated.CalculatorMultiplyInvoker", fqn);
    }

    @Test
    void callerFqn_constructorAnnotation_hasConstructorSuffix() {
        final MergeTree tree = constructorAnnotTree("com/example/Point",
                new Parameter("x", "I"), new Parameter("y", "I"));
        final String fqn = AstEntryPointInjector.resolveCallerFqn(tree, true);
        assertEquals("com.example.generated.PointConstructor", fqn);
    }

    @Test
    void callerFqn_invokerOnConstructor_hasInvokerSuffix() {
        final MergeTree tree = invokerOnConstructorTree("com/example/Point",
                new Parameter("x", "I"));
        final String fqn = AstEntryPointInjector.resolveCallerFqn(tree, false);
        assertEquals("com.example.generated.PointInvoker", fqn);
    }

    @Test
    void callerFqn_defaultPackage_noPackagePrefix() {
        final MergeTree tree = invokerTree("Widget", "process", false,
                new Parameter("v", "I"));
        final String fqn = AstEntryPointInjector.resolveCallerFqn(tree, false);
        assertEquals("WidgetProcessInvoker", fqn);
    }

    // =========================================================================
    // tryCreate — smoke-test under javac
    // =========================================================================

    /**
     * When the tests run under javac (as they always do in this project),
     * {@link AstEntryPointInjector#tryCreate} should successfully reflect into
     * the javac internals and return a non-null injector.
     */
    @Test
    void tryCreate_underJavac_returnsNonNull() throws Exception {
        // Compile a trivial source string; during compilation the processor's
        // init() is called, which calls AstEntryPointInjector.tryCreate().
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Test must run with a JDK, not a JRE");

        final AstEntryPointInjector[] captured = {null};

        final javax.annotation.processing.AbstractProcessor capturingProcessor =
                new javax.annotation.processing.AbstractProcessor() {
            @Override
            public java.util.Set<String> getSupportedAnnotationTypes() {
                return java.util.Set.of("*");
            }
            @Override
            public javax.lang.model.SourceVersion getSupportedSourceVersion() {
                return javax.lang.model.SourceVersion.latestSupported();
            }
            @Override
            public synchronized void init(final ProcessingEnvironment env) {
                super.init(env);
                captured[0] = AstEntryPointInjector.tryCreate(env);
            }
            @Override
            public boolean process(final java.util.Set<? extends javax.lang.model.element.TypeElement> a,
                                   final javax.annotation.processing.RoundEnvironment r) {
                return false;
            }
        };

        final DiagnosticCollector<JavaFileObject> diag = new DiagnosticCollector<>();
        try (final StandardJavaFileManager fm =
                     compiler.getStandardFileManager(diag, null, null)) {

            final JavaFileObject src = new SimpleJavaFileObject(
                    URI.create("string:///Dummy.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
                    return "class Dummy {}";
                }
            };

            final JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fm, diag, List.of(), null, List.of(src));
            task.setProcessors(List.of(capturingProcessor));
            task.call();
        }

        assertNotNull(captured[0],
                "AstEntryPointInjector.tryCreate() should return non-null under javac");
    }
}
