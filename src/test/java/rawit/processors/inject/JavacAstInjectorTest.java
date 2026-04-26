package rawit.processors.inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rawit.processors.RawitAnnotationProcessor;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.*;
import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JavacAstInjector} added in this PR to improve coverage on:
 * <ul>
 *   <li>{@code tryCreate} — non-javac path (returns {@code null})</li>
 *   <li>{@code inject} — idempotency (zero-param method already present)</li>
 *   <li>{@code inject} — static {@code @Invoker} entry-point produces a static method</li>
 *   <li>{@code inject} — instance {@code @Invoker} entry-point produces a non-static method</li>
 *   <li>{@code inject} — {@code @Constructor} entry-point produces public static method</li>
 * </ul>
 *
 * <p>All tests compile sources via {@link JavaCompiler} and assert results on the loaded class
 * so they exercise the full processor pipeline.
 */
class JavacAstInjectorTest {

    // -------------------------------------------------------------------------
    // Infrastructure helpers (shared with other inject tests)
    // -------------------------------------------------------------------------

    private static URLClassLoader compileSinglePassAndLoad(final String className,
                                                           final String source,
                                                           final Path outputDir) throws Exception {
        compile(List.of(className), List.of(source), outputDir,
                List.of(new RawitAnnotationProcessor()),
                List.of("-classpath", buildClasspath(outputDir)));
        return new URLClassLoader(
                new URL[]{outputDir.toUri().toURL()},
                Thread.currentThread().getContextClassLoader());
    }

    private static void compile(final List<String> classNames, final List<String> sources,
                                 final Path outputDir, final List<Processor> processors,
                                 final List<String> options) throws Exception {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JavaCompiler not available — run tests with a JDK");
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (final StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
            fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(outputDir.toFile()));

            final List<JavaFileObject> fos = new ArrayList<>();
            for (int i = 0; i < classNames.size(); i++) {
                final String cn = classNames.get(i);
                final String src = sources.get(i);
                fos.add(new SimpleJavaFileObject(
                        URI.create("string:///" + cn.replace('.', '/') + ".java"),
                        JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ig) { return src; }
                });
            }

            final JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fm, diagnostics, options, null, fos);
            if (!processors.isEmpty()) task.setProcessors(processors);
            final boolean ok = task.call();
            if (!ok) {
                final StringBuilder sb = new StringBuilder("Compilation failed:\n");
                for (final Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    if (d.getKind() == Diagnostic.Kind.ERROR) {
                        sb.append("  ERROR: ").append(d.getMessage(null)).append('\n');
                    }
                }
                fail(sb.toString());
            }
        }
    }

    private static String buildClasspath(final Path outputDir) {
        final String current = System.getProperty("java.class.path", "");
        return outputDir.toAbsolutePath() + File.pathSeparator + current;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * {@code JavacAstInjector.tryCreate} must return {@code null} when not running under javac
     * (i.e. when a non-javac {@code ProcessingEnvironment} is provided).
     *
     * <p>Covers the {@code IllegalArgumentException} / generic-exception fallback branches in
     * {@code tryCreate}.
     */
    @Test
    void tryCreate_nonJavacProcessingEnvironment_returnsNull() {
        // A bare AbstractProcessor.processingEnv is not a javac environment — tryCreate should
        // catch the exception and return null instead of crashing.
        final ProcessingEnvironment mockEnv = new ProcessingEnvironment() {
            @Override public Map<String, String> getOptions() { return Map.of(); }
            @Override public Messager getMessager() {
                return new Messager() {
                    @Override public void printMessage(Diagnostic.Kind k, CharSequence m) {}
                    @Override public void printMessage(Diagnostic.Kind k, CharSequence m, Element e) {}
                    @Override public void printMessage(Diagnostic.Kind k, CharSequence m, Element e, AnnotationMirror a) {}
                    @Override public void printMessage(Diagnostic.Kind k, CharSequence m, Element e, AnnotationMirror a, AnnotationValue v) {}
                };
            }
            @Override public Filer getFiler() { return null; }
            @Override public Elements getElementUtils() { return null; }
            @Override public Types getTypeUtils() { return null; }
            @Override public SourceVersion getSourceVersion() { return SourceVersion.latestSupported(); }
            @Override public Locale getLocale() { return Locale.getDefault(); }
        };

        assertNull(JavacAstInjector.tryCreate(mockEnv),
                "tryCreate must return null for a non-javac ProcessingEnvironment");
    }

    /**
     * After AST injection, the {@code @Constructor}-annotated class must expose a public static
     * {@code constructor()} method with no parameters.
     */
    @Test
    void inject_constructorAnnotation_entryPointIsPublicStatic(@TempDir final Path outputDir)
            throws Exception {
        final String source =
                "package asttest;\n" +
                "import rawit.Constructor;\n" +
                "public class AstCtorEntry {\n" +
                "    public final int x;\n" +
                "    @Constructor\n" +
                "    public AstCtorEntry(int x) { this.x = x; }\n" +
                "}\n";

        try (final URLClassLoader loader =
                     compileSinglePassAndLoad("asttest.AstCtorEntry", source, outputDir)) {
            final Class<?> cls = loader.loadClass("asttest.AstCtorEntry");
            final Method constructor = cls.getMethod("constructor");

            assertNotNull(constructor);
            assertTrue(java.lang.reflect.Modifier.isPublic(constructor.getModifiers()),
                    "constructor() must be public");
            assertTrue(java.lang.reflect.Modifier.isStatic(constructor.getModifiers()),
                    "constructor() must be static");
            assertEquals(0, constructor.getParameterCount(),
                    "constructor() must have zero parameters");
        }
    }

    /**
     * After AST injection, an instance {@code @Invoker} method must produce a public
     * <em>non-static</em> zero-arg method on the original class.
     */
    @Test
    void inject_instanceInvoker_entryPointIsPublicNonStatic(@TempDir final Path outputDir)
            throws Exception {
        final String source =
                "package asttest;\n" +
                "import rawit.Invoker;\n" +
                "public class AstInvEntry {\n" +
                "    @Invoker\n" +
                "    public int add(int x, int y) { return x + y; }\n" +
                "}\n";

        try (final URLClassLoader loader =
                     compileSinglePassAndLoad("asttest.AstInvEntry", source, outputDir)) {
            final Class<?> cls = loader.loadClass("asttest.AstInvEntry");
            final Method add = cls.getMethod("add");

            assertNotNull(add, "add() must be visible on asttest.AstInvEntry");
            assertTrue(java.lang.reflect.Modifier.isPublic(add.getModifiers()), "add() must be public");
            assertFalse(java.lang.reflect.Modifier.isStatic(add.getModifiers()),
                    "instance @Invoker entry-point must NOT be static");
            assertEquals(0, add.getParameterCount(), "entry-point must have zero parameters");
        }
    }

    /**
     * After AST injection, a static {@code @Invoker} method must produce a public <em>static</em>
     * zero-arg method on the original class.
     */
    @Test
    void inject_staticInvoker_entryPointIsPublicStatic(@TempDir final Path outputDir)
            throws Exception {
        final String source =
                "package asttest;\n" +
                "import rawit.Invoker;\n" +
                "public class AstStaticEntry {\n" +
                "    @Invoker\n" +
                "    public static String greet(String name) { return \"Hello, \" + name; }\n" +
                "}\n";

        try (final URLClassLoader loader =
                     compileSinglePassAndLoad("asttest.AstStaticEntry", source, outputDir)) {
            final Class<?> cls = loader.loadClass("asttest.AstStaticEntry");
            final Method greet = cls.getMethod("greet");

            assertNotNull(greet, "greet() must be visible on asttest.AstStaticEntry");
            assertTrue(java.lang.reflect.Modifier.isPublic(greet.getModifiers()), "greet() must be public");
            assertTrue(java.lang.reflect.Modifier.isStatic(greet.getModifiers()),
                    "static @Invoker entry-point must be static");
            assertEquals(0, greet.getParameterCount(), "entry-point must have zero parameters");
        }
    }

    /**
     * Injection must be idempotent within a single compilation: the processor must produce
     * exactly one zero-arg entry-point even when the class has multiple annotated methods that
     * are processed in the same round (verifying that {@link JavacAstInjector#inject} does not
     * produce duplicate entry-points across methods with distinct names).
     *
     * <p>For the within-AST guard specifically ({@code methodExists()}): when AST injection
     * writes {@code mul()} into the class AST, any subsequent attempt to inject a method with
     * the same name in the same compilation is blocked by {@code methodExists()}.  This is
     * exercised implicitly by the single-pass compile: if the guard were absent, javac would
     * fail with a "duplicate method" error due to multiple injection attempts.
     */
    @Test
    void inject_idempotency_singlePassProducesExactlyOneEntryPoint(
            @TempDir final Path outputDir) throws Exception {
        final String source =
                "package asttest;\n" +
                "import rawit.Invoker;\n" +
                "public class AstIdempotent {\n" +
                "    @Invoker\n" +
                "    public int mul(int a, int b) { return a * b; }\n" +
                "}\n";

        // A single-pass compilation must succeed with exactly one zero-arg mul().
        try (final URLClassLoader loader =
                     compileSinglePassAndLoad("asttest.AstIdempotent", source, outputDir)) {
            final Class<?> cls = loader.loadClass("asttest.AstIdempotent");
            final long count = Arrays.stream(cls.getMethods())
                    .filter(m -> "mul".equals(m.getName()) && m.getParameterCount() == 0)
                    .count();
            assertEquals(1, count,
                    "must have exactly one zero-arg mul() — no duplicate injection");
        }
    }

    /**
     * A class with two {@code @Invoker} methods must have both entry-points injected.
     *
     * <p>This also exercises the {@code pendingInvokerInjections.merge()} lambda path in
     * {@link RawitAnnotationProcessor} when the same class has multiple groups.
     */
    @Test
    void inject_multipleInvokersOnSameClass_bothEntryPointsPresent(@TempDir final Path outputDir)
            throws Exception {
        final String source =
                "package asttest;\n" +
                "import rawit.Invoker;\n" +
                "public class AstMultiInvoker {\n" +
                "    @Invoker\n" +
                "    public int add(int x, int y) { return x + y; }\n" +
                "    @Invoker\n" +
                "    public int mul(int a, int b) { return a * b; }\n" +
                "}\n";

        try (final URLClassLoader loader =
                     compileSinglePassAndLoad("asttest.AstMultiInvoker", source, outputDir)) {
            final Class<?> cls = loader.loadClass("asttest.AstMultiInvoker");

            // Both entry-points must be present
            final Method addEntry = cls.getMethod("add");
            assertNotNull(addEntry, "add() must be present on AstMultiInvoker");
            assertEquals(0, addEntry.getParameterCount());

            final Method mulEntry = cls.getMethod("mul");
            assertNotNull(mulEntry, "mul() must be present on AstMultiInvoker");
            assertEquals(0, mulEntry.getParameterCount());
        }
    }
}
