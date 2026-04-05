package rawit.processors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.Processor;
import javax.tools.*;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for {@link RawitAnnotationProcessor}.
 *
 * <p>Each test:
 * <ol>
 *   <li>Compiles a small annotated Java source string <em>without</em> the processor (pass 1)
 *       so that the {@code .class} file exists on disk before the processor runs.</li>
 *   <li>Runs the processor in {@code -proc:only} mode (pass 2) so that the processor can read
 *       the {@code .class} file, inject the parameterless overload, and write the generated
 *       stage-interface source files — without javac overwriting the modified class file.</li>
 *   <li>Compiles the generated source files without the processor (pass 3).</li>
 *   <li>Loads the resulting {@code .class} files via {@link URLClassLoader} and uses reflection
 *       to exercise the full invoker chain, asserting that the result equals direct invocation.</li>
 * </ol>
 *
 * <p>Validates: Requirements 6.6, 8.1, 8.2, 12.4, 15, 16, 19.3, 19.4
 */
class RawitAnnotationProcessorIntegrationTest {

    // =========================================================================
    // Infrastructure helpers
    // =========================================================================

    /**
     * Compiles {@code source} into {@code outputDir} without any annotation processor.
     * This is pass 1 — it produces the {@code .class} file that the processor needs.
     */
    private static void compileWithoutProcessor(final String className,
                                                 final String source,
                                                 final Path outputDir) throws Exception {
        compile(className, source, outputDir, List.of(), List.of("-proc:none"));
    }

    /**
     * Runs the processor in {@code -proc:only} mode to inject bytecode and generate source files,
     * then compiles the generated source files without the processor.
     *
     * <p>Using {@code -proc:only} prevents javac from recompiling the original source and
     * overwriting the class file that the processor just modified.
     */
    private static void compileWithProcessor(final String className,
                                              final String source,
                                              final Path outputDir) throws Exception {
        // Step 1: Run processor in proc-only mode — injects bytecode, writes generated .java files
        compile(className, source, outputDir,
                List.of(new RawitAnnotationProcessor()),
                List.of("-proc:only", "-classpath", buildClasspath(outputDir)));

        // Step 2: Compile the generated .java files without the processor
        final java.io.File[] generatedJavaFiles = outputDir.toFile().listFiles(
                f -> f.getName().endsWith(".java") && !f.getName().equals(className + ".java"));
        if (generatedJavaFiles != null && generatedJavaFiles.length > 0) {
            final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (final StandardJavaFileManager fm =
                         compiler.getStandardFileManager(diagnostics, null, null)) {
                fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
                fm.setLocation(StandardLocation.CLASS_PATH, List.of(outputDir.toFile()));

                final List<JavaFileObject> sourceFiles = new java.util.ArrayList<>();
                for (final java.io.File jf : generatedJavaFiles) {
                    sourceFiles.add(new SimpleJavaFileObject(jf.toURI(), JavaFileObject.Kind.SOURCE) {
                        @Override
                        public CharSequence getCharContent(boolean ignoreEncodingErrors)
                                throws java.io.IOException {
                            return java.nio.file.Files.readString(jf.toPath());
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

    /**
     * Builds a classpath string that includes the test output directory plus the current JVM
     * classpath (so the processor can find the annotation classes).
     */
    private static String buildClasspath(final Path outputDir) {
        final String currentClasspath = System.getProperty("java.class.path", "");
        return outputDir.toAbsolutePath() + File.pathSeparator + currentClasspath;
    }

    /**
     * Runs all compilation passes and returns a {@link URLClassLoader} rooted at
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

    /**
     * Invokes a method on an object using reflection, with {@code setAccessible(true)} to bypass
     * access checks on private accumulator classes.
     */
    private static Object invoke(final Object target, final String methodName,
                                  final Class<?>[] paramTypes, final Object... args) throws Exception {
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

    private static Object invoke(final Object target, final String methodName) throws Exception {
        return invoke(target, methodName, new Class<?>[0]);
    }

    private static Object invokeInt(final Object target, final String methodName,
                                     final int arg) throws Exception {
        return invoke(target, methodName, new Class<?>[]{int.class}, arg);
    }

    private static Object invokeString(final Object target, final String methodName,
                                        final String arg) throws Exception {
        return invoke(target, methodName, new Class<?>[]{String.class}, arg);
    }

    private static Object invokeBoolean(final Object target, final String methodName,
                                         final boolean arg) throws Exception {
        return invoke(target, methodName, new Class<?>[]{boolean.class}, arg);
    }

    // =========================================================================
    // Multi-source compilation helpers (for tests with multiple source files)
    // =========================================================================

    /**
     * Compiles multiple source files into {@code outputDir} without any annotation processor.
     */
    private static void compileMultipleWithoutProcessor(final List<String> classNames,
                                                         final List<String> sources,
                                                         final Path outputDir) throws Exception {
        compileMultiple(classNames, sources, outputDir, List.of(), List.of("-proc:none"));
    }

    /**
     * Runs the processor in {@code -proc:only} mode on multiple source files, then compiles
     * the generated source files without the processor.
     */
    private static void compileMultipleWithProcessor(final List<String> classNames,
                                                      final List<String> sources,
                                                      final Path outputDir) throws Exception {
        // Step 1: Run processor in proc-only mode
        compileMultiple(classNames, sources, outputDir,
                List.of(new RawitAnnotationProcessor()),
                List.of("-proc:only", "-classpath", buildClasspath(outputDir)));

        // Step 2: Compile the generated .java files without the processor
        // Build set of original source file names (may include package paths)
        final java.util.Set<String> originalNames = new java.util.HashSet<>();
        for (final String cn : classNames) {
            originalNames.add(cn.replace('.', File.separatorChar) + ".java");
        }

        // Walk the output directory to find all generated .java files (including in subdirs)
        final java.util.List<java.io.File> generatedJavaFiles = new java.util.ArrayList<>();
        try (final var stream = java.nio.file.Files.walk(outputDir)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                  .filter(p -> {
                      final String relative = outputDir.relativize(p).toString();
                      return !originalNames.contains(relative);
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

                final java.util.List<JavaFileObject> sourceFiles = new java.util.ArrayList<>();
                for (final java.io.File jf : generatedJavaFiles) {
                    sourceFiles.add(new SimpleJavaFileObject(jf.toURI(), JavaFileObject.Kind.SOURCE) {
                        @Override
                        public CharSequence getCharContent(boolean ignoreEncodingErrors)
                                throws java.io.IOException {
                            return java.nio.file.Files.readString(jf.toPath());
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

            final java.util.List<JavaFileObject> sourceFileObjects = new java.util.ArrayList<>();
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
     * Runs all compilation passes for multiple source files and returns a {@link URLClassLoader}.
     */
    private static URLClassLoader compileMultipleAndLoad(final List<String> classNames,
                                                          final List<String> sources,
                                                          final Path outputDir) throws Exception {
        compileMultipleWithoutProcessor(classNames, sources, outputDir);
        compileMultipleWithProcessor(classNames, sources, outputDir);
        return new URLClassLoader(
                new URL[]{outputDir.toUri().toURL()},
                Thread.currentThread().getContextClassLoader());
    }

    // =========================================================================
    // Test 1 — Instance method: @Invoker on int add(int x, int y)
    // Validates: Requirements 6.6, 8.1, 8.2
    // =========================================================================

    @Test
    void instanceMethod_invokerChain_equalsDirectInvocation(@TempDir final Path outputDir)
            throws Exception {
        final String source =
                "import rawit.Invoker;\n" +
                "public class AddInstance {\n" +
                "    @Invoker\n" +
                "    public int add(int x, int y) { return x + y; }\n" +
                "}\n";

        try (final URLClassLoader loader = compileAndLoad("AddInstance", source, outputDir)) {
            final Class<?> fooClass = loader.loadClass("AddInstance");
            final Object foo = fooClass.getDeclaredConstructor().newInstance();

            // foo.add().x(3).y(4).invoke() should equal foo.add(3, 4) == 7
            final Object addStage = fooClass.getMethod("add").invoke(foo);
            final Object xStage = invokeInt(addStage, "x", 3);
            final Object invokeStage = invokeInt(xStage, "y", 4);
            final Object result = invoke(invokeStage, "invoke");

            assertEquals(7, result, "add().x(3).y(4).invoke() must equal add(3, 4) = 7");

            // Round-trip equivalence
            final int direct = (int) fooClass.getMethod("add", int.class, int.class)
                    .invoke(foo, 3, 4);
            assertEquals(direct, result, "chain result must equal direct invocation");
        }
    }

    // =========================================================================
    // Test 2 — Static method: @Invoker on static int add(int x, int y)
    // Validates: Requirements 6.6, 8.1, 8.2
    // =========================================================================

    @Test
    void staticMethod_invokerChain_equalsDirectInvocation(@TempDir final Path outputDir)
            throws Exception {
        final String source =
                "import rawit.Invoker;\n" +
                "public class AddStatic {\n" +
                "    @Invoker\n" +
                "    public static int add(int x, int y) { return x + y; }\n" +
                "}\n";

        try (final URLClassLoader loader = compileAndLoad("AddStatic", source, outputDir)) {
            final Class<?> fooClass = loader.loadClass("AddStatic");

            // AddStatic.add().x(3).y(4).invoke() should equal AddStatic.add(3, 4) == 7
            final Object addStage = fooClass.getMethod("add").invoke(null);
            final Object xStage = invokeInt(addStage, "x", 3);
            final Object invokeStage = invokeInt(xStage, "y", 4);
            final Object result = invoke(invokeStage, "invoke");

            assertEquals(7, result, "static add().x(3).y(4).invoke() must equal add(3, 4) = 7");

            final int direct = (int) fooClass.getMethod("add", int.class, int.class)
                    .invoke(null, 3, 4);
            assertEquals(direct, result, "chain result must equal direct invocation");
        }
    }

    // =========================================================================
    // Test 3 — Constructor with @Invoker: parameterless overload injected
    // Validates: Requirements 6.6, 8.1, 8.2
    // =========================================================================

    @Test
    void constructorWithInvoker_invokerChain_equalsDirectInvocation(@TempDir final Path outputDir)
            throws Exception {
        final String source =
                "import rawit.Invoker;\n" +
                "public class PointCurry {\n" +
                "    public final int x;\n" +
                "    public final int y;\n" +
                "    @Invoker\n" +
                "    public PointCurry(int x, int y) { this.x = x; this.y = y; }\n" +
                "}\n";

        try (final URLClassLoader loader = compileAndLoad("PointCurry", source, outputDir)) {
            final Class<?> pointClass = loader.loadClass("PointCurry");

            // PointCurry.pointcurry().x(1).y(2).invoke() should produce a PointCurry with x=1, y=2
            final Object xStage = pointClass.getMethod("pointcurry").invoke(null);
            final Object yStage = invokeInt(xStage, "x", 1);
            final Object invokeStage = invokeInt(yStage, "y", 2);
            final Object result = invoke(invokeStage, "invoke");

            assertNotNull(result, "invoke() must return a PointCurry instance");
            assertEquals(1, pointClass.getField("x").get(result), "x must be 1");
            assertEquals(2, pointClass.getField("y").get(result), "y must be 2");

            // Round-trip: direct construction
            final Object direct = pointClass.getDeclaredConstructor(int.class, int.class)
                    .newInstance(1, 2);
            assertEquals(pointClass.getField("x").get(direct),
                    pointClass.getField("x").get(result));
            assertEquals(pointClass.getField("y").get(direct),
                    pointClass.getField("y").get(result));
        }
    }

    // =========================================================================
    // Test 4 — @Constructor annotation: Foo.constructor().id(1).name("bar").construct()
    // Validates: Requirements 15, 16, 19.3, 19.4
    // =========================================================================

    @Test
    void constructorAnnotation_constructChain_createsInstance(@TempDir final Path outputDir)
            throws Exception {
        final String source =
                "import rawit.Constructor;\n" +
                "public class Person {\n" +
                "    public final int id;\n" +
                "    public final String name;\n" +
                "    @Constructor\n" +
                "    public Person(int id, String name) { this.id = id; this.name = name; }\n" +
                "}\n";

        try (final URLClassLoader loader = compileAndLoad("Person", source, outputDir)) {
            final Class<?> personClass = loader.loadClass("Person");

            // Person.constructor().id(1).name("bar").construct() should create Person(1, "bar")
            final Object idStage = personClass.getMethod("constructor").invoke(null);
            final Object nameStage = invokeInt(idStage, "id", 1);
            final Object constructStage = invokeString(nameStage, "name", "bar");
            final Object result = invoke(constructStage, "construct");

            assertNotNull(result, "construct() must return a Person instance");
            assertInstanceOf(personClass, result, "result must be a Person");
            assertEquals(1, personClass.getField("id").get(result), "id must be 1");
            assertEquals("bar", personClass.getField("name").get(result), "name must be 'bar'");

            // Round-trip equivalence
            final Object direct = personClass.getDeclaredConstructor(int.class, String.class)
                    .newInstance(1, "bar");
            assertEquals(personClass.getField("id").get(direct),
                    personClass.getField("id").get(result));
            assertEquals(personClass.getField("name").get(direct),
                    personClass.getField("name").get(result));
        }
    }

    // =========================================================================
    // Test 5 — Overload group with branching: two @Invoker methods with same name
    // Validates: Requirements 8.1, 8.2
    // =========================================================================

    @Test
    void overloadGroupWithBranching_bothBranchesWork(@TempDir final Path outputDir)
            throws Exception {
        final String source =
                "import rawit.Invoker;\n" +
                "public class Calculator {\n" +
                "    @Invoker\n" +
                "    public int compute(int x, int y) { return x + y; }\n" +
                "    @Invoker\n" +
                "    public int compute(int x, String label) { return x + label.length(); }\n" +
                "}\n";

        try (final URLClassLoader loader = compileAndLoad("Calculator", source, outputDir)) {
            final Class<?> calcClass = loader.loadClass("Calculator");
            final Object calc = calcClass.getDeclaredConstructor().newInstance();

            // Branch 1: compute().x(5).y(3).invoke() == 8
            final Object computeStage = calcClass.getMethod("compute").invoke(calc);
            final Object xStage1 = invokeInt(computeStage, "x", 5);
            final Object invokeStage1 = invokeInt(xStage1, "y", 3);
            final Object result1 = invoke(invokeStage1, "invoke");
            assertEquals(8, result1, "compute().x(5).y(3).invoke() must equal compute(5, 3) = 8");

            // Branch 2: compute().x(5).label("hi").invoke() == 5 + 2 = 7
            final Object computeStage2 = calcClass.getMethod("compute").invoke(calc);
            final Object xStage2 = invokeInt(computeStage2, "x", 5);
            final Object invokeStage2 = invokeString(xStage2, "label", "hi");
            final Object result2 = invoke(invokeStage2, "invoke");
            assertEquals(7, result2,
                    "compute().x(5).label(\"hi\").invoke() must equal compute(5, \"hi\") = 7");
        }
    }

    // =========================================================================
    // Test 6 — Prefix overload: bar(int x, int y) and bar(int x, int y, int z)
    // Validates: Requirements 12.4, 8.1
    // =========================================================================

    @Test
    void prefixOverload_bothInvokePathsWork(@TempDir final Path outputDir) throws Exception {
        final String source =
                "import rawit.Invoker;\n" +
                "public class Adder {\n" +
                "    @Invoker\n" +
                "    public int bar(int x, int y) { return x + y; }\n" +
                "    @Invoker\n" +
                "    public int bar(int x, int y, int z) { return x + y + z; }\n" +
                "}\n";

        try (final URLClassLoader loader = compileAndLoad("Adder", source, outputDir)) {
            final Class<?> adderClass = loader.loadClass("Adder");
            final Object adder = adderClass.getDeclaredConstructor().newInstance();

            // Short path: bar().x(10).y(20).invoke() == 30
            final Object barStage = adderClass.getMethod("bar").invoke(adder);
            final Object xStage = invokeInt(barStage, "x", 10);
            final Object yStage = invokeInt(xStage, "y", 20);
            final Object shortResult = invoke(yStage, "invoke");
            assertEquals(30, shortResult,
                    "bar().x(10).y(20).invoke() must equal bar(10, 20) = 30");

            // Long path: bar().x(10).y(20).z(30).invoke() == 60
            final Object barStage2 = adderClass.getMethod("bar").invoke(adder);
            final Object xStage2 = invokeInt(barStage2, "x", 10);
            final Object yStage2 = invokeInt(xStage2, "y", 20);
            final Object zStage = invokeInt(yStage2, "z", 30);
            final Object longResult = invoke(zStage, "invoke");
            assertEquals(60, longResult,
                    "bar().x(10).y(20).z(30).invoke() must equal bar(10, 20, 30) = 60");
        }
    }

    // =========================================================================
    // Test 7 — @Constructor on a record produces working staged API
    // Validates: Requirements 5.1, 5.2, 5.3, 6.1, 6.2
    // =========================================================================

    @Test
    void recordConstructor_stagedApi_createsRecordInstance(@TempDir final Path outputDir)
            throws Exception {
        final String source =
                "import rawit.Constructor;\n" +
                "@Constructor\n" +
                "public record Point(int x, int y) {}\n";

        try (final URLClassLoader loader = compileAndLoad("Point", source, outputDir)) {
            final Class<?> pointClass = loader.loadClass("Point");

            // Point.constructor().x(1).y(2).construct() should create Point(1, 2)
            final Object xStage = pointClass.getMethod("constructor").invoke(null);
            final Object yStage = invokeInt(xStage, "x", 1);
            final Object constructStage = invokeInt(yStage, "y", 2);
            final Object result = invoke(constructStage, "construct");

            assertNotNull(result, "construct() must return a Point instance");
            assertInstanceOf(pointClass, result, "result must be a Point");

            // Verify component values via accessor methods
            assertEquals(1, pointClass.getMethod("x").invoke(result), "x must be 1");
            assertEquals(2, pointClass.getMethod("y").invoke(result), "y must be 2");

            // Round-trip equivalence with direct construction
            final Object direct = pointClass.getDeclaredConstructor(int.class, int.class)
                    .newInstance(1, 2);
            assertEquals(pointClass.getMethod("x").invoke(direct),
                    pointClass.getMethod("x").invoke(result));
            assertEquals(pointClass.getMethod("y").invoke(direct),
                    pointClass.getMethod("y").invoke(result));
        }
    }

    // =========================================================================
    // Test 8 — @Constructor on a record with mixed types (String, int, boolean)
    // Validates: Requirements 7.1, 7.2
    // =========================================================================

    @Test
    void recordConstructor_mixedTypes_stagedApiWorksCorrectly(@TempDir final Path outputDir)
            throws Exception {
        final String source =
                "import rawit.Constructor;\n" +
                "@Constructor\n" +
                "public record Config(String name, int port, boolean secure) {}\n";

        try (final URLClassLoader loader = compileAndLoad("Config", source, outputDir)) {
            final Class<?> configClass = loader.loadClass("Config");

            // Config.constructor().name("server").port(8080).secure(true).construct()
            final Object nameStage = configClass.getMethod("constructor").invoke(null);
            final Object portStage = invokeString(nameStage, "name", "server");
            final Object secureStage = invokeInt(portStage, "port", 8080);
            final Object constructStage = invokeBoolean(secureStage, "secure", true);
            final Object result = invoke(constructStage, "construct");

            assertNotNull(result, "construct() must return a Config instance");
            assertInstanceOf(configClass, result, "result must be a Config");

            // Verify component values via accessor methods
            assertEquals("server", configClass.getMethod("name").invoke(result),
                    "name must be 'server'");
            assertEquals(8080, configClass.getMethod("port").invoke(result),
                    "port must be 8080");
            assertEquals(true, configClass.getMethod("secure").invoke(result),
                    "secure must be true");

            // Round-trip equivalence with direct construction
            final Object direct = configClass.getDeclaredConstructor(
                    String.class, int.class, boolean.class).newInstance("server", 8080, true);
            assertEquals(configClass.getMethod("name").invoke(direct),
                    configClass.getMethod("name").invoke(result));
            assertEquals(configClass.getMethod("port").invoke(direct),
                    configClass.getMethod("port").invoke(result));
            assertEquals(configClass.getMethod("secure").invoke(direct),
                    configClass.getMethod("secure").invoke(result));
        }
    }

    // =========================================================================
    // Test 9 — Both record and regular class in same compilation round
    // Validates: Requirements 8.1, 8.2, 8.3
    // =========================================================================

    @Test
    void recordAndClass_sameCompilationRound_bothProduceIndependentApis(
            @TempDir final Path outputDir) throws Exception {
        // Use different packages so each gets its own independent Constructor caller class
        final String recordSource =
                "package recpkg;\n" +
                "import rawit.Constructor;\n" +
                "@Constructor\n" +
                "public record Coord(int x, int y) {}\n";

        final String classSource =
                "package clspkg;\n" +
                "import rawit.Constructor;\n" +
                "public class Pair {\n" +
                "    public final int a;\n" +
                "    public final int b;\n" +
                "    @Constructor\n" +
                "    public Pair(int a, int b) { this.a = a; this.b = b; }\n" +
                "}\n";

        try (final URLClassLoader loader = compileMultipleAndLoad(
                List.of("recpkg.Coord", "clspkg.Pair"),
                List.of(recordSource, classSource),
                outputDir)) {

            // --- Verify the record's staged API ---
            final Class<?> coordClass = loader.loadClass("recpkg.Coord");
            final Object coordXStage = coordClass.getMethod("constructor").invoke(null);
            final Object coordYStage = invokeInt(coordXStage, "x", 10);
            final Object coordConstructStage = invokeInt(coordYStage, "y", 20);
            final Object coord = invoke(coordConstructStage, "construct");

            assertNotNull(coord, "construct() must return a Coord instance");
            assertInstanceOf(coordClass, coord, "result must be a Coord");
            assertEquals(10, coordClass.getMethod("x").invoke(coord), "x must be 10");
            assertEquals(20, coordClass.getMethod("y").invoke(coord), "y must be 20");

            // --- Verify the regular class's staged API ---
            final Class<?> pairClass = loader.loadClass("clspkg.Pair");
            final Object pairAStage = pairClass.getMethod("constructor").invoke(null);
            final Object pairBStage = invokeInt(pairAStage, "a", 30);
            final Object pairConstructStage = invokeInt(pairBStage, "b", 40);
            final Object pair = invoke(pairConstructStage, "construct");

            assertNotNull(pair, "construct() must return a Pair instance");
            assertInstanceOf(pairClass, pair, "result must be a Pair");
            assertEquals(30, pairClass.getField("a").get(pair), "a must be 30");
            assertEquals(40, pairClass.getField("b").get(pair), "b must be 40");
        }
    }
}
