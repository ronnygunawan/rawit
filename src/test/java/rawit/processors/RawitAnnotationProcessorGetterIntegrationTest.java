package rawit.processors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.Processor;
import javax.tools.*;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for {@code @Getter} processing in
 * {@link RawitAnnotationProcessor}.
 *
 * <p>Each test:
 * <ol>
 *   <li>Compiles a small annotated Java source string <em>without</em> the processor (pass 1)
 *       so that the {@code .class} file exists on disk.</li>
 *   <li>Runs the processor in {@code -proc:only} mode (pass 2) so that the processor can read
 *       the {@code .class} file and inject getter methods — without javac overwriting the
 *       modified class file.</li>
 *   <li>Loads the resulting {@code .class} files via {@link URLClassLoader} and uses reflection
 *       to exercise the generated getters.</li>
 * </ol>
 *
 * <p>Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 3.1, 3.2, 4.1, 4.2, 4.3, 5.1,
 * 6.1, 6.2, 7.1, 8.6, 8.7, 9.1, 9.2, 10.1, 10.2, 11.1, 12.1
 */
class RawitAnnotationProcessorGetterIntegrationTest {

    // =========================================================================
    // Infrastructure helpers
    // =========================================================================

    /**
     * Compiles {@code source} into {@code outputDir} without any annotation processor.
     */
    private static void compileWithoutProcessor(final String className,
                                                 final String source,
                                                 final Path outputDir) throws Exception {
        compile(className, source, outputDir, List.of(), List.of("-proc:none"));
    }

    /**
     * Runs the processor in {@code -proc:only} mode to inject getter bytecode.
     */
    private static void compileWithProcessor(final String className,
                                              final String source,
                                              final Path outputDir) throws Exception {
        compile(className, source, outputDir,
                List.of(new RawitAnnotationProcessor()),
                List.of("-proc:only", "-classpath", buildClasspath(outputDir)));
    }

    private static void compile(final String className,
                                 final String source,
                                 final Path outputDir,
                                 final List<Processor> processors,
                                 final List<String> options) throws Exception {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JavaCompiler not available — run tests with a JDK, not a JRE");

        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (final StandardJavaFileManager fm =
                     compiler.getStandardFileManager(diagnostics, null, null)) {

            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
            fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(outputDir.toFile()));

            final JavaFileObject sourceFile = new SimpleJavaFileObject(
                    URI.create("string:///" + className.replace('.', '/') + ".java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };

            final JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fm, diagnostics, options, null, List.of(sourceFile));

            if (!processors.isEmpty()) {
                task.setProcessors(processors);
            }

            final boolean success = task.call();
            if (!success) {
                final StringBuilder sb = new StringBuilder("Compilation failed:\n");
                for (final Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    if (d.getKind() == Diagnostic.Kind.ERROR) {
                        sb.append("  ERROR: ").append(d.getMessage(null));
                        if (d.getSource() != null) {
                            sb.append(" [in ").append(d.getSource().getName()).append("]");
                        }
                        sb.append('\n');
                    }
                }
                fail(sb.toString());
            }
        }
    }

    /**
     * Compiles multiple source files without any annotation processor.
     */
    private static void compileMultipleWithoutProcessor(final List<String> classNames,
                                                         final List<String> sources,
                                                         final Path outputDir) throws Exception {
        compileMultiple(classNames, sources, outputDir, List.of(), List.of("-proc:none"));
    }

    /**
     * Runs the processor in {@code -proc:only} mode on multiple source files.
     */
    private static void compileMultipleWithProcessor(final List<String> classNames,
                                                      final List<String> sources,
                                                      final Path outputDir) throws Exception {
        compileMultiple(classNames, sources, outputDir,
                List.of(new RawitAnnotationProcessor()),
                List.of("-proc:only", "-classpath", buildClasspath(outputDir)));
    }

    private static void compileMultiple(final List<String> classNames,
                                         final List<String> sources,
                                         final Path outputDir,
                                         final List<Processor> processors,
                                         final List<String> options) throws Exception {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JavaCompiler not available — run tests with a JDK, not a JRE");

        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (final StandardJavaFileManager fm =
                     compiler.getStandardFileManager(diagnostics, null, null)) {

            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
            fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(outputDir.toFile()));

            final List<JavaFileObject> sourceFileObjects = new ArrayList<>();
            for (int i = 0; i < classNames.size(); i++) {
                final String cn = classNames.get(i);
                final String src = sources.get(i);
                sourceFileObjects.add(new SimpleJavaFileObject(
                        URI.create("string:///" + cn.replace('.', '/') + ".java"),
                        JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return src;
                    }
                });
            }

            final JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fm, diagnostics, options, null, sourceFileObjects);

            if (!processors.isEmpty()) {
                task.setProcessors(processors);
            }

            final boolean success = task.call();
            if (!success) {
                final StringBuilder sb = new StringBuilder("Compilation failed:\n");
                for (final Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    if (d.getKind() == Diagnostic.Kind.ERROR) {
                        sb.append("  ERROR: ").append(d.getMessage(null));
                        if (d.getSource() != null) {
                            sb.append(" [in ").append(d.getSource().getName()).append("]");
                        }
                        sb.append('\n');
                    }
                }
                fail(sb.toString());
            }
        }
    }

    /**
     * Compiles with the processor and returns the diagnostics (does NOT fail on errors).
     * Used for error-detection tests.
     */
    private static List<Diagnostic<? extends JavaFileObject>> compileWithProcessorCapturingErrors(
            final String className,
            final String source,
            final Path outputDir) throws Exception {
        // Pass 1: compile without processor
        compileWithoutProcessor(className, source, outputDir);

        // Pass 2: run processor, capture diagnostics
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (final StandardJavaFileManager fm =
                     compiler.getStandardFileManager(diagnostics, null, null)) {

            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
            fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(outputDir.toFile()));

            final JavaFileObject sourceFile = new SimpleJavaFileObject(
                    URI.create("string:///" + className.replace('.', '/') + ".java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };

            final JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fm, diagnostics,
                    List.of("-proc:only", "-classpath", buildClasspath(outputDir)),
                    null, List.of(sourceFile));
            task.setProcessors(List.of(new RawitAnnotationProcessor()));
            task.call(); // don't assert success — we expect errors
        }
        return diagnostics.getDiagnostics();
    }

    /**
     * Multi-source variant of error-capturing compilation.
     */
    private static List<Diagnostic<? extends JavaFileObject>> compileMultipleWithProcessorCapturingErrors(
            final List<String> classNames,
            final List<String> sources,
            final Path outputDir) throws Exception {
        // Pass 1: compile without processor
        compileMultipleWithoutProcessor(classNames, sources, outputDir);

        // Pass 2: run processor, capture diagnostics
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (final StandardJavaFileManager fm =
                     compiler.getStandardFileManager(diagnostics, null, null)) {

            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
            fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(outputDir.toFile()));

            final List<JavaFileObject> sourceFileObjects = new ArrayList<>();
            for (int i = 0; i < classNames.size(); i++) {
                final String cn = classNames.get(i);
                final String src = sources.get(i);
                sourceFileObjects.add(new SimpleJavaFileObject(
                        URI.create("string:///" + cn.replace('.', '/') + ".java"),
                        JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return src;
                    }
                });
            }

            final JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fm, diagnostics,
                    List.of("-proc:only", "-classpath", buildClasspath(outputDir)),
                    null, sourceFileObjects);
            task.setProcessors(List.of(new RawitAnnotationProcessor()));
            task.call();
        }
        return diagnostics.getDiagnostics();
    }

    private static String buildClasspath(final Path outputDir) {
        final String currentClasspath = System.getProperty("java.class.path", "");
        return outputDir.toAbsolutePath() + File.pathSeparator + currentClasspath;
    }

    /**
     * Runs both compilation passes and returns a {@link URLClassLoader} rooted at
     * {@code outputDir}.
     */
    private static URLClassLoader compileAndLoad(final String className,
                                                  final String source,
                                                  final Path outputDir) throws Exception {
        compileWithoutProcessor(className, source, outputDir);
        compileWithProcessor(className, source, outputDir);
        return new URLClassLoader(
                new URL[]{outputDir.toUri().toURL()},
                Thread.currentThread().getContextClassLoader());
    }

    private static URLClassLoader compileMultipleAndLoad(final List<String> classNames,
                                                          final List<String> sources,
                                                          final Path outputDir) throws Exception {
        compileMultipleWithoutProcessor(classNames, sources, outputDir);
        compileMultipleWithProcessor(classNames, sources, outputDir);
        return new URLClassLoader(
                new URL[]{outputDir.toUri().toURL()},
                Thread.currentThread().getContextClassLoader());
    }

    private static List<String> errorMessages(final List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        final List<String> errors = new ArrayList<>();
        for (final Diagnostic<? extends JavaFileObject> d : diagnostics) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                errors.add(d.getMessage(null));
            }
        }
        return errors;
    }

    // =========================================================================
    // Test 1 — Basic instance String field → getName()
    // Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 7.1
    // =========================================================================

    @Test
    void instanceStringField_getterReturnsFieldValue(@TempDir final Path outputDir) throws Exception {
        final String source =
                "package testpkg;\n" +
                "import rawit.Getter;\n" +
                "public class Person {\n" +
                "    @Getter\n" +
                "    private String name = \"Alice\";\n" +
                "    public Person() {}\n" +
                "}\n";

        try (final URLClassLoader loader = compileAndLoad("testpkg.Person", source, outputDir)) {
            final Class<?> clazz = loader.loadClass("testpkg.Person");
            final Object instance = clazz.getDeclaredConstructor().newInstance();

            final Method getter = clazz.getMethod("getName");
            assertTrue(Modifier.isPublic(getter.getModifiers()), "getter must be public");
            assertFalse(Modifier.isStatic(getter.getModifiers()), "getter must be instance method");
            assertEquals(String.class, getter.getReturnType(), "return type must be String");

            final Object result = getter.invoke(instance);
            assertEquals("Alice", result, "getName() must return the field value");
        }
    }

    // =========================================================================
    // Test 2 — Static int field → getCount()
    // Validates: Requirements 2.1, 3.1
    // =========================================================================

    @Test
    void staticIntField_getterReturnsFieldValue(@TempDir final Path outputDir) throws Exception {
        final String source =
                "package testpkg;\n" +
                "import rawit.Getter;\n" +
                "public class Counter {\n" +
                "    @Getter\n" +
                "    private static int count = 42;\n" +
                "}\n";

        try (final URLClassLoader loader = compileAndLoad("testpkg.Counter", source, outputDir)) {
            final Class<?> clazz = loader.loadClass("testpkg.Counter");

            final Method getter = clazz.getMethod("getCount");
            assertTrue(Modifier.isPublic(getter.getModifiers()), "getter must be public");
            assertTrue(Modifier.isStatic(getter.getModifiers()), "getter must be static");
            assertEquals(int.class, getter.getReturnType(), "return type must be int");

            final Object result = getter.invoke(null);
            assertEquals(42, result, "getCount() must return the field value");
        }
    }

    // =========================================================================
    // Test 3 — Primitive boolean field → isActive()
    // Validates: Requirements 4.1
    // =========================================================================

    @Test
    void primitiveBooleanField_getterUsesIsPrefix(@TempDir final Path outputDir) throws Exception {
        final String source =
                "package testpkg;\n" +
                "import rawit.Getter;\n" +
                "public class Flags {\n" +
                "    @Getter\n" +
                "    private boolean active = true;\n" +
                "    public Flags() {}\n" +
                "}\n";

        try (final URLClassLoader loader = compileAndLoad("testpkg.Flags", source, outputDir)) {
            final Class<?> clazz = loader.loadClass("testpkg.Flags");
            final Object instance = clazz.getDeclaredConstructor().newInstance();

            final Method getter = clazz.getMethod("isActive");
            assertTrue(Modifier.isPublic(getter.getModifiers()), "getter must be public");
            assertEquals(boolean.class, getter.getReturnType(), "return type must be boolean");

            final Object result = getter.invoke(instance);
            assertEquals(true, result, "isActive() must return the field value");
        }
    }

    // =========================================================================
    // Test 4 — Primitive boolean with is-prefix: isReady → isReady()
    // Validates: Requirements 4.2
    // =========================================================================

    @Test
    void primitiveBooleanWithIsPrefix_getterNameIsFieldName(@TempDir final Path outputDir) throws Exception {
        final String source =
                "package testpkg;\n" +
                "import rawit.Getter;\n" +
                "public class Status {\n" +
                "    @Getter\n" +
                "    private boolean isReady = true;\n" +
                "    public Status() {}\n" +
                "}\n";

        try (final URLClassLoader loader = compileAndLoad("testpkg.Status", source, outputDir)) {
            final Class<?> clazz = loader.loadClass("testpkg.Status");
            final Object instance = clazz.getDeclaredConstructor().newInstance();

            final Method getter = clazz.getMethod("isReady");
            assertEquals(boolean.class, getter.getReturnType(), "return type must be boolean");

            final Object result = getter.invoke(instance);
            assertEquals(true, result, "isReady() must return the field value");
        }
    }

    // =========================================================================
    // Test 5 — Boxed Boolean field → getEnabled()
    // Validates: Requirements 5.1
    // =========================================================================

    @Test
    void boxedBooleanField_getterUsesGetPrefix(@TempDir final Path outputDir) throws Exception {
        final String source =
                "package testpkg;\n" +
                "import rawit.Getter;\n" +
                "public class Toggle {\n" +
                "    @Getter\n" +
                "    private Boolean enabled = Boolean.TRUE;\n" +
                "    public Toggle() {}\n" +
                "}\n";

        try (final URLClassLoader loader = compileAndLoad("testpkg.Toggle", source, outputDir)) {
            final Class<?> clazz = loader.loadClass("testpkg.Toggle");
            final Object instance = clazz.getDeclaredConstructor().newInstance();

            final Method getter = clazz.getMethod("getEnabled");
            assertTrue(Modifier.isPublic(getter.getModifiers()), "getter must be public");
            assertEquals(Boolean.class, getter.getReturnType(), "return type must be Boolean");

            final Object result = getter.invoke(instance);
            assertEquals(Boolean.TRUE, result, "getEnabled() must return the field value");
        }
    }

    // =========================================================================
    // Test 6 — Generic field: List<String> items → getItems() with preserved generic type
    // Validates: Requirements 9.1, 9.2
    // =========================================================================

    @Test
    void genericField_getterPreservesGenericType(@TempDir final Path outputDir) throws Exception {
        final String source =
                "package testpkg;\n" +
                "import rawit.Getter;\n" +
                "import java.util.List;\n" +
                "import java.util.ArrayList;\n" +
                "public class Container {\n" +
                "    @Getter\n" +
                "    private List<String> items = new ArrayList<>();\n" +
                "    public Container() { items.add(\"hello\"); }\n" +
                "}\n";

        try (final URLClassLoader loader = compileAndLoad("testpkg.Container", source, outputDir)) {
            final Class<?> clazz = loader.loadClass("testpkg.Container");
            final Object instance = clazz.getDeclaredConstructor().newInstance();

            final Method getter = clazz.getMethod("getItems");
            assertTrue(Modifier.isPublic(getter.getModifiers()), "getter must be public");
            assertEquals(List.class, getter.getReturnType(), "raw return type must be List");

            // Verify generic return type is preserved
            final Type genericReturnType = getter.getGenericReturnType();
            assertInstanceOf(ParameterizedType.class, genericReturnType,
                    "return type must be parameterized");
            final ParameterizedType pt = (ParameterizedType) genericReturnType;
            assertEquals(List.class, pt.getRawType(), "raw type must be List");
            assertEquals(1, pt.getActualTypeArguments().length, "must have 1 type argument");
            assertEquals(String.class, pt.getActualTypeArguments()[0],
                    "type argument must be String");

            @SuppressWarnings("unchecked")
            final List<String> result = (List<String>) getter.invoke(instance);
            assertEquals(1, result.size(), "items must have 1 element");
            assertEquals("hello", result.get(0), "first item must be 'hello'");
        }
    }

    // =========================================================================
    // Test 7 — Collision detection: existing getName() + @Getter String name → error
    // Validates: Requirements 6.1
    // =========================================================================

    @Test
    void sameClassMethodCollision_emitsError(@TempDir final Path outputDir) throws Exception {
        final String source =
                "package testpkg;\n" +
                "import rawit.Getter;\n" +
                "public class Collision {\n" +
                "    @Getter\n" +
                "    private String name = \"Alice\";\n" +
                "    public String getName() { return \"manual\"; }\n" +
                "}\n";

        final List<String> errors = errorMessages(
                compileWithProcessorCapturingErrors("testpkg.Collision", source, outputDir));

        assertTrue(errors.stream().anyMatch(e -> e.contains("conflicts with existing method")),
                "expected collision error, got: " + errors);
    }

    // =========================================================================
    // Test 8 — Volatile field rejection
    // Validates: Requirements 10.1
    // =========================================================================

    @Test
    void volatileField_emitsError(@TempDir final Path outputDir) throws Exception {
        final String source =
                "package testpkg;\n" +
                "import rawit.Getter;\n" +
                "public class VolatileTest {\n" +
                "    @Getter\n" +
                "    private volatile int x = 1;\n" +
                "}\n";

        final List<String> errors = errorMessages(
                compileWithProcessorCapturingErrors("testpkg.VolatileTest", source, outputDir));

        assertTrue(errors.stream().anyMatch(e -> e.contains("not supported on volatile fields")),
                "expected volatile rejection error, got: " + errors);
    }

    // =========================================================================
    // Test 9 — Transient field rejection
    // Validates: Requirements 10.2
    // =========================================================================

    @Test
    void transientField_emitsError(@TempDir final Path outputDir) throws Exception {
        final String source =
                "package testpkg;\n" +
                "import rawit.Getter;\n" +
                "public class TransientTest {\n" +
                "    @Getter\n" +
                "    private transient String y = \"temp\";\n" +
                "}\n";

        final List<String> errors = errorMessages(
                compileWithProcessorCapturingErrors("testpkg.TransientTest", source, outputDir));

        assertTrue(errors.stream().anyMatch(e -> e.contains("not supported on transient fields")),
                "expected transient rejection error, got: " + errors);
    }
}
