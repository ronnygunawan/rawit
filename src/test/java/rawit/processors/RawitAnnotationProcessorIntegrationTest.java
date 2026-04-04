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
}
