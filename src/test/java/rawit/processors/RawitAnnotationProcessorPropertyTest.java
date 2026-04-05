package rawit.processors;

import net.jqwik.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.Processor;
import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for the end-to-end {@link RawitAnnotationProcessor} pipeline.
 *
 * <p>Each property runs with a small number of tries because compilation is expensive.
 * Uses the same three-pass compilation infrastructure as
 * {@link RawitAnnotationProcessorIntegrationTest}.
 *
 * <p>Validates: Requirements 6.6, 7.1, 7.2, 8.1, 8.2, 8.4, 12.4, 19.3, 19.4, 20.7
 */
class RawitAnnotationProcessorPropertyTest {

    // =========================================================================
    // Arbitraries
    // =========================================================================

    /** Simple int values that are safe to use as method arguments. */
    @Provide
    Arbitrary<Integer> smallInt() {
        return Arbitraries.integers().between(-1000, 1000);
    }

    /** Distinct method names for Property 16. */
    @Provide
    Arbitrary<List<String>> distinctMethodNames() {
        return Arbitraries.of("add", "multiply", "subtract", "divide", "compute", "process")
                .list().ofMinSize(2).ofMaxSize(3)
                .filter(names -> names.stream().distinct().count() == names.size());
    }

    // =========================================================================
    // Compilation infrastructure (mirrors RawitAnnotationProcessorIntegrationTest)
    // =========================================================================

    private static void compileWithoutProcessor(final String className,
                                                 final String source,
                                                 final Path outputDir) throws Exception {
        compile(className, source, outputDir, List.of(), List.of("-proc:none"));
    }

    private static void compileWithProcessor(final String className,
                                              final String source,
                                              final Path outputDir) throws Exception {
        // Pass 1: run processor in proc-only mode
        compile(className, source, outputDir,
                List.of(new RawitAnnotationProcessor()),
                List.of("-proc:only", "-classpath", buildClasspath(outputDir)));

        // Pass 2: compile generated .java files without the processor
        // Walk subdirectories because generated files now live in a generated/ subdirectory
        final String classRelativePath = className.replace('.', File.separatorChar) + ".java";
        final java.util.List<File> generatedJavaFiles = new java.util.ArrayList<>();
        try (final var stream = Files.walk(outputDir)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                  .filter(p -> {
                      final String relative = outputDir.relativize(p).toString();
                      return !relative.equals(classRelativePath);
                  })
                  .forEach(p -> generatedJavaFiles.add(p.toFile()));
        }
        if (!generatedJavaFiles.isEmpty()) {
            final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (final StandardJavaFileManager fm =
                         compiler.getStandardFileManager(diagnostics, null, null)) {
                fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
                fm.setLocation(StandardLocation.CLASS_PATH, List.of(outputDir.toFile()));

                final List<JavaFileObject> sourceFiles = new java.util.ArrayList<>();
                for (final File jf : generatedJavaFiles) {
                    sourceFiles.add(new SimpleJavaFileObject(jf.toURI(), JavaFileObject.Kind.SOURCE) {
                        @Override
                        public CharSequence getCharContent(boolean ignoreEncodingErrors)
                                throws IOException {
                            return Files.readString(jf.toPath());
                        }
                    });
                }

                final boolean success = compiler.getTask(
                        null, fm, diagnostics, List.of("-proc:none"), null, sourceFiles).call();
                if (!success) {
                    final StringBuilder sb = new StringBuilder("Generated source compilation failed:\n");
                    for (final Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                        if (d.getKind() == Diagnostic.Kind.ERROR) {
                            sb.append("  ERROR: ").append(d.getMessage(null)).append('\n');
                        }
                    }
                    fail(sb.toString());
                }
            }
        }
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

    private static String buildClasspath(final Path outputDir) {
        final String currentClasspath = System.getProperty("java.class.path", "");
        return outputDir.toAbsolutePath() + File.pathSeparator + currentClasspath;
    }

    private static URLClassLoader compileAndLoad(final String className,
                                                  final String source,
                                                  final Path outputDir) throws Exception {
        compileWithoutProcessor(className, source, outputDir);
        compileWithProcessor(className, source, outputDir);
        return new URLClassLoader(
                new URL[]{outputDir.toUri().toURL()},
                Thread.currentThread().getContextClassLoader());
    }

    /** Reflective invocation helper — searches declared methods and interfaces. */
    private static Object reflectInvoke(final Object target, final String methodName,
                                         final Class<?>[] paramTypes,
                                         final Object... args) throws Exception {
        java.lang.reflect.Method method = null;
        try {
            method = target.getClass().getMethod(methodName, paramTypes);
        } catch (NoSuchMethodException e) {
            for (final Class<?> iface : target.getClass().getInterfaces()) {
                try {
                    method = iface.getMethod(methodName, paramTypes);
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
        }
        if (method == null) {
            throw new NoSuchMethodException(methodName + " not found on " + target.getClass());
        }
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Object reflectInvoke(final Object target, final String methodName)
            throws Exception {
        return reflectInvoke(target, methodName, new Class<?>[0]);
    }

    private static Object reflectInvokeInt(final Object target, final String methodName,
                                            final int arg) throws Exception {
        return reflectInvoke(target, methodName, new Class<?>[]{int.class}, arg);
    }

    /** Deletes a directory tree recursively (best-effort). */
    private static void deleteRecursively(final Path dir) {
        try {
            if (Files.exists(dir)) {
                Files.walk(dir).sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        } catch (final IOException ignored) {}
    }

    // =========================================================================
    // Property 1: Round-trip equivalence — chain invocation equals direct invocation
    // Feature: curry-to-invoker-rename, Property 1: Round-trip equivalence is preserved
    // =========================================================================

    /**
     * For any int values x and y, calling the invoker chain add().x(x).y(y).invoke()
     * must equal the direct call add(x, y).
     *
     * Validates: Requirements 6.6, 8.1, 8.2, 12.4, 19.3, 19.4
     */
    @Property(tries = 5)
    void property1_roundTripEquivalenceWithInvoker(
            @ForAll("smallInt") int x,
            @ForAll("smallInt") int y
    ) throws Exception {
        // Feature: curry-to-invoker-rename, Property 1: Round-trip equivalence is preserved
        final String simpleClassName = "RoundTripAdd_" + Math.abs(x) + "_" + Math.abs(y)
                + "_" + Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
        final String className = "testpkg." + simpleClassName;
        final String source =
                "package testpkg;\n" +
                "import rawit.Invoker;\n" +
                "public class " + simpleClassName + " {\n" +
                "    @Invoker\n" +
                "    public int add(int x, int y) { return x + y; }\n" +
                "}\n";

        final Path outputDir = Files.createTempDirectory("prop1_");
        try (final URLClassLoader loader = compileAndLoad(className, source, outputDir)) {
            final Class<?> cls = loader.loadClass(className);
            final Object instance = cls.getDeclaredConstructor().newInstance();

            // Chain: instance.add().x(x).y(y).invoke()
            final Object addStage = cls.getMethod("add").invoke(instance);
            final Object xStage = reflectInvokeInt(addStage, "x", x);
            final Object invokeStage = reflectInvokeInt(xStage, "y", y);
            final Object chainResult = reflectInvoke(invokeStage, "invoke");

            // Direct: instance.add(x, y)
            final int directResult = (int) cls.getMethod("add", int.class, int.class)
                    .invoke(instance, x, y);

            assertEquals(directResult, chainResult,
                    "chain add().x(" + x + ").y(" + y + ").invoke() must equal add(" + x + "," + y + ")");
        } finally {
            deleteRecursively(outputDir);
        }
    }

    // =========================================================================
    // Property 16: Multiple annotations produce separate Caller_Classes
    // Feature: curry-to-invoker-rename, Property 16: Multiple annotations produce separate Caller_Classes
    // =========================================================================

    /**
     * For any class with multiple @Invoker-annotated methods with distinct names, after processing,
     * the class must contain a separate Caller_Class for each annotated method with no name collisions.
     *
     * Validates: Requirements 7.1, 7.2
     */
    @Property(tries = 5)
    void property16_multipleAnnotationsProduceSeparateCallerClasses(
            @ForAll("distinctMethodNames") List<String> methodNames
    ) throws Exception {
        // Feature: curry-to-invoker-rename, Property 16: Multiple annotations produce separate Caller_Classes
        final String simpleClassName = "MultiInvoker_" + methodNames.size()
                + "_" + Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
        final String className = "testpkg." + simpleClassName;

        // Build source with one @Invoker method per name, each returning x + y
        final StringBuilder methods = new StringBuilder();
        for (final String name : methodNames) {
            methods.append("    @Invoker\n")
                   .append("    public int ").append(name).append("(int x, int y) { return x + y; }\n");
        }

        final String source =
                "package testpkg;\n" +
                "import rawit.Invoker;\n" +
                "public class " + simpleClassName + " {\n" +
                methods +
                "}\n";

        final Path outputDir = Files.createTempDirectory("prop16_");
        try (final URLClassLoader loader = compileAndLoad(className, source, outputDir)) {
            final Class<?> cls = loader.loadClass(className);
            final Object instance = cls.getDeclaredConstructor().newInstance();

            // For each method, verify a separate Caller_Class exists and the chain works.
            // The Caller_Class is generated as a top-level class in the generated subpackage,
            // named <ClassName><MethodPascalCase>Invoker.
            for (final String name : methodNames) {
                final String pascalMethod = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                final String callerClassName = "testpkg.generated." + simpleClassName + pascalMethod + "Invoker";

                // The Caller_Class is a top-level class in the generated subpackage
                final Class<?> callerClass = loader.loadClass(callerClassName);
                assertNotNull(callerClass,
                        "Caller_Class " + callerClassName + " must exist for method " + name);

                // Verify the chain works: name().x(3).y(4).invoke() == 7
                final Object stage0 = cls.getMethod(name).invoke(instance);
                final Object stage1 = reflectInvokeInt(stage0, "x", 3);
                final Object stage2 = reflectInvokeInt(stage1, "y", 4);
                final Object result = reflectInvoke(stage2, "invoke");
                assertEquals(7, result,
                        name + "().x(3).y(4).invoke() must equal " + name + "(3,4) = 7");
            }

            // Verify no name collisions: all Caller_Class names are distinct
            final long distinctCallerNames = methodNames.stream()
                    .map(n -> "testpkg.generated." + simpleClassName + Character.toUpperCase(n.charAt(0)) + n.substring(1) + "Invoker")
                    .distinct().count();
            assertEquals(methodNames.size(), distinctCallerNames,
                    "all Caller_Class names must be distinct");
        } finally {
            deleteRecursively(outputDir);
        }
    }

    // =========================================================================
    // Property 18: Invoke idempotency — calling invoke() multiple times produces equal results
    // Feature: curry-to-invoker-rename, Property 18: Invoke idempotency — calling invoke() multiple times produces equal results
    // =========================================================================

    /**
     * For any int values x and y, calling .invoke() multiple times on the same InvokeStageInvoker
     * instance must produce equal results.
     *
     * Validates: Requirements 8.4
     */
    @Property(tries = 5)
    void property18_invokeIdempotency(
            @ForAll("smallInt") int x,
            @ForAll("smallInt") int y
    ) throws Exception {
        // Feature: curry-to-invoker-rename, Property 18: Invoke idempotency — calling invoke() multiple times produces equal results
        final String simpleClassName = "IdempotentAdd_" + Math.abs(x) + "_" + Math.abs(y)
                + "_" + Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
        final String className = "testpkg." + simpleClassName;
        final String source =
                "package testpkg;\n" +
                "import rawit.Invoker;\n" +
                "public class " + simpleClassName + " {\n" +
                "    @Invoker\n" +
                "    public int add(int x, int y) { return x + y; }\n" +
                "}\n";

        final Path outputDir = Files.createTempDirectory("prop18_");
        try (final URLClassLoader loader = compileAndLoad(className, source, outputDir)) {
            final Class<?> cls = loader.loadClass(className);
            final Object instance = cls.getDeclaredConstructor().newInstance();

            // Build the chain up to the InvokeStageInvoker
            final Object addStage = cls.getMethod("add").invoke(instance);
            final Object xStage = reflectInvokeInt(addStage, "x", x);
            final Object invokeStage = reflectInvokeInt(xStage, "y", y);

            // Call invoke() multiple times on the same InvokeStageInvoker instance
            final Object result1 = reflectInvoke(invokeStage, "invoke");
            final Object result2 = reflectInvoke(invokeStage, "invoke");
            final Object result3 = reflectInvoke(invokeStage, "invoke");

            assertEquals(result1, result2,
                    "invoke() called twice on same instance must produce equal results");
            assertEquals(result1, result3,
                    "invoke() called three times on same instance must produce equal results");
        } finally {
            deleteRecursively(outputDir);
        }
    }
}
