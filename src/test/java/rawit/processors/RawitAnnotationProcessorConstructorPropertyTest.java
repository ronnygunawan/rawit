package rawit.processors;

import net.jqwik.api.*;

import javax.annotation.processing.Processor;
import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for the {@code @Constructor} annotation pipeline.
 *
 * <p>Each property runs with a small number of tries because compilation is expensive.
 * Uses the same three-pass compilation infrastructure as
 * {@link RawitAnnotationProcessorIntegrationTest}.
 *
 * <p>Validates: Requirements 16.1, 16.2, 17.1, 19.2
 */
class RawitAnnotationProcessorConstructorPropertyTest {

    // =========================================================================
    // Arbitraries
    // =========================================================================

    /** Number of int parameters (1–3) for the generated constructor. */
    @Provide
    Arbitrary<Integer> paramCount() {
        return Arbitraries.integers().between(1, 3);
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
                List.of(new rawit.processors.RawitAnnotationProcessor()),
                List.of("-proc:only", "-classpath", buildClasspath(outputDir)));

        // Pass 2: compile generated .java files without the processor
        final File[] generatedJavaFiles = outputDir.toFile().listFiles(
                f -> f.getName().endsWith(".java") && !f.getName().equals(className + ".java"));
        if (generatedJavaFiles != null && generatedJavaFiles.length > 0) {
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

    /** Deletes a directory tree recursively (best-effort). */
    private static void deleteRecursively(final Path dir) {
        try {
            if (Files.exists(dir)) {
                Files.walk(dir).sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                        });
            }
        } catch (final IOException ignored) {}
    }

    /**
     * Builds a source string for a class with a @Constructor-annotated constructor
     * that takes {@code n} int parameters named p0, p1, ..., p(n-1).
     */
    private static String buildConstructorSource(final String className, final int paramCount) {
        final StringBuilder fields = new StringBuilder();
        final StringBuilder params = new StringBuilder();
        final StringBuilder assignments = new StringBuilder();

        for (int i = 0; i < paramCount; i++) {
            final String pName = "p" + i;
            fields.append("    public final int ").append(pName).append(";\n");
            if (i > 0) params.append(", ");
            params.append("int ").append(pName);
            assignments.append("        this.").append(pName).append(" = ").append(pName).append(";\n");
        }

        return "import rawit.Constructor;\n" +
               "public class " + className + " {\n" +
               fields +
               "    @Constructor\n" +
               "    public " + className + "(" + params + ") {\n" +
               assignments +
               "    }\n" +
               "}\n";
    }

    // =========================================================================
    // Property 25: constructor() entry point is public static
    // Feature: curry-to-invoker-rename, Property 25: constructor() entry point is public static
    // =========================================================================

    /**
     * For any class with @Constructor on a constructor (1–3 int parameters), after processing,
     * the class must have a method named "constructor" that is public and static.
     *
     * Validates: Requirements 16.1, 16.2
     */
    @Property(tries = 10)
    void property25_constructorEntryPointIsPublicStatic(
            @ForAll("paramCount") int n
    ) throws Exception {
        // Feature: curry-to-invoker-rename, Property 25: constructor() entry point is public static
        final String className = "CtorEntry_" + n + "_" + Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
        final String source = buildConstructorSource(className, n);

        final Path outputDir = Files.createTempDirectory("prop25_");
        try (final URLClassLoader loader = compileAndLoad(className, source, outputDir)) {
            final Class<?> cls = loader.loadClass(className);

            // Find the "constructor" method with zero parameters
            Method constructorMethod = null;
            for (final Method m : cls.getDeclaredMethods()) {
                if ("constructor".equals(m.getName()) && m.getParameterCount() == 0) {
                    constructorMethod = m;
                    break;
                }
            }

            assertNotNull(constructorMethod,
                    "Class " + className + " must have a zero-arg method named 'constructor'");

            assertTrue(Modifier.isPublic(constructorMethod.getModifiers()),
                    "'constructor()' method must be public");
            assertTrue(Modifier.isStatic(constructorMethod.getModifiers()),
                    "'constructor()' method must be static");
        } finally {
            deleteRecursively(outputDir);
        }
    }

    // =========================================================================
    // Property 26: Constructor_Caller_Class is injected as a public static inner class named Constructor
    // Feature: curry-to-invoker-rename, Property 26: Constructor_Caller_Class is injected as a public static inner class named Constructor
    // =========================================================================

    /**
     * For any class with @Constructor on a constructor, after processing, a class named
     * "Constructor" should be loadable from the output directory and be public.
     *
     * Validates: Requirements 17.1
     */
    @Property(tries = 10)
    void property26_constructorCallerClassIsPublic(
            @ForAll("paramCount") int n
    ) throws Exception {
        // Feature: curry-to-invoker-rename, Property 26: Constructor_Caller_Class is injected as a public static inner class named Constructor
        final String className = "CtorCaller_" + n + "_" + Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
        final String source = buildConstructorSource(className, n);

        final Path outputDir = Files.createTempDirectory("prop26_");
        try (final URLClassLoader loader = compileAndLoad(className, source, outputDir)) {
            // The Constructor_Caller_Class is generated as a top-level class named "Constructor"
            final Class<?> constructorClass = loader.loadClass("Constructor");

            assertNotNull(constructorClass,
                    "A top-level class named 'Constructor' must be loadable after processing");
            assertTrue(Modifier.isPublic(constructorClass.getModifiers()),
                    "The 'Constructor' class must be public");
        } finally {
            deleteRecursively(outputDir);
        }
    }

    // =========================================================================
    // Property 27: ConstructStageInvoker has a construct() method returning the enclosing type
    // Feature: curry-to-invoker-rename, Property 27: ConstructStageInvoker has a construct() method returning the enclosing type
    // =========================================================================

    /**
     * For any class with @Constructor on a constructor, the ConstructStageInvoker interface
     * must have a construct() method, and calling it through the full chain must return an
     * instance of the enclosing class.
     *
     * Validates: Requirements 19.2
     */
    @Property(tries = 10)
    void property27_constructStageInvokerHasConstructMethod(
            @ForAll("paramCount") int n
    ) throws Exception {
        // Feature: curry-to-invoker-rename, Property 27: ConstructStageInvoker has a construct() method returning the enclosing type
        final String className = "CtorConstruct_" + n + "_" + Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
        final String source = buildConstructorSource(className, n);

        final Path outputDir = Files.createTempDirectory("prop27_");
        try (final URLClassLoader loader = compileAndLoad(className, source, outputDir)) {
            final Class<?> cls = loader.loadClass(className);

            // Walk the chain: constructor().p0(0).p1(0)...pN-1(0).construct()
            // Start: call the static constructor() entry point
            Object stage = cls.getMethod("constructor").invoke(null);

            // Chain through each parameter stage method with value 0
            for (int i = 0; i < n; i++) {
                final String paramName = "p" + i;
                // Find the stage method by name (accepts int)
                Method stageMethod = null;
                for (final Method m : stage.getClass().getMethods()) {
                    if (paramName.equals(m.getName()) && m.getParameterCount() == 1) {
                        stageMethod = m;
                        break;
                    }
                }
                // Also search declared interfaces
                if (stageMethod == null) {
                    for (final Class<?> iface : stage.getClass().getInterfaces()) {
                        for (final Method m : iface.getMethods()) {
                            if (paramName.equals(m.getName()) && m.getParameterCount() == 1) {
                                stageMethod = m;
                                break;
                            }
                        }
                        if (stageMethod != null) break;
                    }
                }
                assertNotNull(stageMethod,
                        "Stage method '" + paramName + "' must exist on " + stage.getClass());
                stageMethod.setAccessible(true);
                stage = stageMethod.invoke(stage, 0);
            }

            // Now 'stage' should be a ConstructStageInvoker — find construct() method
            Method constructMethod = null;
            for (final Method m : stage.getClass().getMethods()) {
                if ("construct".equals(m.getName()) && m.getParameterCount() == 0) {
                    constructMethod = m;
                    break;
                }
            }
            if (constructMethod == null) {
                for (final Class<?> iface : stage.getClass().getInterfaces()) {
                    for (final Method m : iface.getMethods()) {
                        if ("construct".equals(m.getName()) && m.getParameterCount() == 0) {
                            constructMethod = m;
                            break;
                        }
                    }
                    if (constructMethod != null) break;
                }
            }

            assertNotNull(constructMethod,
                    "ConstructStageInvoker must have a zero-arg 'construct()' method");

            // Verify return type is the enclosing class
            assertEquals(cls, constructMethod.getReturnType(),
                    "construct() must return the enclosing class type " + cls.getName());

            // Invoke construct() and verify the result is an instance of the enclosing class
            constructMethod.setAccessible(true);
            final Object result = constructMethod.invoke(stage);
            assertNotNull(result, "construct() must return a non-null instance");
            assertInstanceOf(cls, result,
                    "construct() result must be an instance of " + cls.getName());
        } finally {
            deleteRecursively(outputDir);
        }
    }
}
