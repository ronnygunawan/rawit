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

    /** Number of record components (1–3) for Property 1. */
    @Provide
    Arbitrary<Integer> recordComponentCount() {
        return Arbitraries.integers().between(1, 3);
    }

    /**
     * Represents a record component type with its Java source declaration and the expected
     * Java class after compilation (used for reflection-based verification).
     */
    private record ComponentType(String javaSource, Class<?> expectedClass) {}

    /** Arbitrary that picks from a set of representative component types for Property 5. */
    @Provide
    Arbitrary<ComponentType> componentType() {
        return Arbitraries.of(
                new ComponentType("int", int.class),
                new ComponentType("long", long.class),
                new ComponentType("boolean", boolean.class),
                new ComponentType("double", double.class),
                new ComponentType("byte", byte.class),
                new ComponentType("char", char.class),
                new ComponentType("short", short.class),
                new ComponentType("float", float.class),
                new ComponentType("String", String.class),
                new ComponentType("int[]", int[].class),
                new ComponentType("String[]", String[].class)
        );
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
     * Builds a source string for a {@code @Constructor}-annotated record with {@code n}
     * int components named c0, c1, ..., c(n-1).
     * Uses package {@code testpkg} so generated classes in the {@code testpkg.generated}
     * subpackage can reference the record type.
     */
    private static String buildRecordSource(final String recordName, final int componentCount) {
        final StringBuilder components = new StringBuilder();
        for (int i = 0; i < componentCount; i++) {
            if (i > 0) components.append(", ");
            components.append("int c").append(i);
        }
        return "package testpkg;\n" +
               "import rawit.Constructor;\n" +
               "@Constructor\n" +
               "public record " + recordName + "(" + components + ") {}\n";
    }

    /**
     * Builds a source string for a class with a @Constructor-annotated constructor
     * that takes {@code n} int parameters named p0, p1, ..., p(n-1).
     * Uses package {@code testpkg} so generated classes in the {@code testpkg.generated}
     * subpackage can reference the class type.
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

        return "package testpkg;\n" +
               "import rawit.Constructor;\n" +
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
    @Property(tries = 5)
    void property25_constructorEntryPointIsPublicStatic(
            @ForAll("paramCount") int n
    ) throws Exception {
        // Feature: curry-to-invoker-rename, Property 25: constructor() entry point is public static
        final String className = "CtorEntry_" + n + "_" + Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
        final String qualifiedName = "testpkg." + className;
        final String source = buildConstructorSource(className, n);

        final Path outputDir = Files.createTempDirectory("prop25_");
        try (final URLClassLoader loader = compileAndLoad(qualifiedName, source, outputDir)) {
            final Class<?> cls = loader.loadClass(qualifiedName);

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
    // Property 26: Constructor_Caller_Class is a public top-level class named <EnclosingSimpleName>Constructor
    // Feature: generated-class-naming, Property 26: Constructor_Caller_Class is a public top-level class
    // =========================================================================

    /**
     * For any class with @Constructor on a constructor, after processing, a top-level class
     * named "&lt;ClassName&gt;Constructor" should be loadable from the generated subpackage
     * and be public.
     *
     * Validates: Requirements 17.1
     */
    @Property(tries = 5)
    void property26_constructorCallerClassIsPublic(
            @ForAll("paramCount") int n
    ) throws Exception {
        // Feature: generated-class-naming, Property 26: Constructor_Caller_Class is a public top-level class named <EnclosingSimpleName>Constructor
        final String className = "CtorCaller_" + n + "_" + Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
        final String qualifiedName = "testpkg." + className;
        final String source = buildConstructorSource(className, n);

        final Path outputDir = Files.createTempDirectory("prop26_");
        try (final URLClassLoader loader = compileAndLoad(qualifiedName, source, outputDir)) {
            // The Constructor_Caller_Class is generated as a top-level class in the generated subpackage
            // named <ClassName>Constructor (e.g., testpkg.generated.CtorCaller_2_abcConstructor)
            final String constructorClassName = "testpkg.generated." + className + "Constructor";
            final Class<?> constructorClass = loader.loadClass(constructorClassName);

            assertNotNull(constructorClass,
                    "A top-level class named '" + constructorClassName + "' must be loadable after processing");
            assertTrue(Modifier.isPublic(constructorClass.getModifiers()),
                    "The '" + constructorClassName + "' class must be public");
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
    @Property(tries = 5)
    void property27_constructStageInvokerHasConstructMethod(
            @ForAll("paramCount") int n
    ) throws Exception {
        // Feature: curry-to-invoker-rename, Property 27: ConstructStageInvoker has a construct() method returning the enclosing type
        final String className = "CtorConstruct_" + n + "_" + Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
        final String qualifiedName = "testpkg." + className;
        final String source = buildConstructorSource(className, n);

        final Path outputDir = Files.createTempDirectory("prop27_");
        try (final URLClassLoader loader = compileAndLoad(qualifiedName, source, outputDir)) {
            final Class<?> cls = loader.loadClass(qualifiedName);

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

    // =========================================================================
    // Property 1: Record AnnotatedMethod construction correctness
    // Feature: record-constructor-support, Property 1: Record AnnotatedMethod construction correctness
    // =========================================================================

    /**
     * For any record type with N ≥ 1 components, building an AnnotatedMethod from that record
     * SHALL produce a model where enclosingClassName equals the slash-separated binary name of
     * the record, methodName equals "&lt;init&gt;", isConstructor is true,
     * isConstructorAnnotation is true, and parameters is a list of N Parameter entries whose
     * names and type descriptors match the record components in declaration order.
     *
     * <p>We verify this indirectly by compiling a @Constructor-annotated record through the
     * processor and checking the generated staged API: the entry point is {@code constructor()}
     * (proving isConstructorAnnotation=true), the terminal is {@code construct()} (proving
     * isConstructor=true with @Constructor), and there are exactly N stage methods whose names
     * match the record component names in declaration order.
     *
     * <p>Validates: Requirements 3.2, 3.3, 3.4
     */
    @Property(tries = 5)
    void property1_recordAnnotatedMethodConstructionCorrectness(
            @ForAll("recordComponentCount") int n
    ) throws Exception {
        // Feature: record-constructor-support, Property 1: Record AnnotatedMethod construction correctness
        final String recordName = "RecProp1_" + n + "_" + Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
        final String qualifiedName = "testpkg." + recordName;
        final String source = buildRecordSource(recordName, n);

        final Path outputDir = Files.createTempDirectory("prop1_rec_");
        try (final URLClassLoader loader = compileAndLoad(qualifiedName, source, outputDir)) {
            final Class<?> recordClass = loader.loadClass(qualifiedName);

            // 1. Verify constructor() entry point exists (proves enclosingClassName is correct
            //    and isConstructorAnnotation=true — the processor only generates constructor()
            //    for @Constructor-annotated elements)
            final Method entryPoint = recordClass.getMethod("constructor");
            assertNotNull(entryPoint,
                    "Record " + recordName + " must have a zero-arg 'constructor()' entry point");
            assertTrue(Modifier.isPublic(entryPoint.getModifiers()),
                    "'constructor()' must be public");
            assertTrue(Modifier.isStatic(entryPoint.getModifiers()),
                    "'constructor()' must be static");

            // 2. Walk the staged chain: constructor().c0(0).c1(0)...c(N-1)(0).construct()
            //    This verifies parameters match record components in declaration order
            Object stage = entryPoint.invoke(null);

            for (int i = 0; i < n; i++) {
                final String componentName = "c" + i;
                // Find the stage method matching the component name (accepts int)
                final Method stageMethod = findMethod(stage, componentName, 1);
                assertNotNull(stageMethod,
                        "Stage method '" + componentName + "' must exist at position " + i
                                + " (proves parameter order matches record component order)");

                // Verify the stage method accepts int (matching the record component type)
                assertEquals(int.class, stageMethod.getParameterTypes()[0],
                        "Stage method '" + componentName + "' must accept int"
                                + " (proves type descriptor 'I' was correctly derived)");

                stageMethod.setAccessible(true);
                stage = stageMethod.invoke(stage, i);
            }

            // 3. Verify the terminal method is construct() (proves isConstructor=true and
            //    isConstructorAnnotation=true — @Constructor uses construct(), not invoke())
            final Method constructMethod = findMethod(stage, "construct", 0);
            assertNotNull(constructMethod,
                    "Terminal must have 'construct()' (proves isConstructor=true, "
                            + "isConstructorAnnotation=true, methodName='<init>')");

            // 4. Verify construct() returns the record type (proves enclosingClassName is correct)
            assertEquals(recordClass, constructMethod.getReturnType(),
                    "construct() must return " + recordName);

            // 5. Invoke and verify the result is a valid record instance
            constructMethod.setAccessible(true);
            final Object result = constructMethod.invoke(stage);
            assertNotNull(result, "construct() must return a non-null instance");
            assertInstanceOf(recordClass, result,
                    "construct() result must be an instance of " + recordName);

            // 6. Verify the record component values match what we passed through the chain
            for (int i = 0; i < n; i++) {
                final Method accessor = recordClass.getMethod("c" + i);
                assertEquals(i, accessor.invoke(result),
                        "Record component c" + i + " must equal " + i);
            }
        } finally {
            deleteRecursively(outputDir);
        }
    }

    // =========================================================================
    // Property 5: Type descriptor correctness for record components
    // Feature: record-constructor-support, Property 5: Type descriptor correctness for record components
    // =========================================================================

    /**
     * For any record component type — whether primitive, reference, or array — the
     * toTypeDescriptor() method SHALL produce the correct JVM type descriptor.
     *
     * <p>We verify this by compiling a @Constructor-annotated record with a single component
     * of the given type through the processor, then checking via reflection that the generated
     * stage method accepts the correct Java type (which proves the processor derived the correct
     * JVM type descriptor from the record component).
     *
     * <p>Validates: Requirements 7.1, 7.2, 7.3
     */
    @Property(tries = 5)
    void property5_typeDescriptorCorrectnessForRecordComponents(
            @ForAll("componentType") ComponentType compType
    ) throws Exception {
        // Feature: record-constructor-support, Property 5: Type descriptor correctness for record components
        final String recordName = "RecProp5_" + compType.javaSource().replaceAll("[^a-zA-Z0-9]", "")
                + "_" + Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
        final String qualifiedName = "testpkg." + recordName;

        final String source = "package testpkg;\n" +
                "import rawit.Constructor;\n" +
                "@Constructor\n" +
                "public record " + recordName + "(" + compType.javaSource() + " value) {}\n";

        final Path outputDir = Files.createTempDirectory("prop5_rec_");
        try (final URLClassLoader loader = compileAndLoad(qualifiedName, source, outputDir)) {
            final Class<?> recordClass = loader.loadClass(qualifiedName);

            // Call the constructor() entry point
            final Method entryPoint = recordClass.getMethod("constructor");
            assertNotNull(entryPoint,
                    "Record " + recordName + " must have a 'constructor()' entry point");

            final Object stage = entryPoint.invoke(null);

            // Find the stage method for the "value" component
            final Method valueMethod = findMethod(stage, "value", 1);
            assertNotNull(valueMethod,
                    "Stage method 'value' must exist for component type " + compType.javaSource());

            // Verify the parameter type matches the expected Java class
            // This proves toTypeDescriptor() produced the correct JVM descriptor:
            //   int → "I", long → "J", boolean → "Z", String → "Ljava/lang/String;",
            //   int[] → "[I", String[] → "[Ljava/lang/String;", etc.
            assertEquals(compType.expectedClass(), valueMethod.getParameterTypes()[0],
                    "Stage method 'value' must accept " + compType.expectedClass().getName()
                            + " for component type " + compType.javaSource()
                            + " (proves correct JVM type descriptor)");
        } finally {
            deleteRecursively(outputDir);
        }
    }

    // =========================================================================
    // Reflection helper
    // =========================================================================

    /**
     * Finds a method by name and parameter count on the target object, searching both
     * the concrete class and its interfaces.
     */
    private static Method findMethod(final Object target, final String methodName,
                                      final int paramCount) {
        // Search concrete class methods first
        for (final Method m : target.getClass().getMethods()) {
            if (methodName.equals(m.getName()) && m.getParameterCount() == paramCount) {
                return m;
            }
        }
        // Search declared interfaces
        for (final Class<?> iface : target.getClass().getInterfaces()) {
            for (final Method m : iface.getMethods()) {
                if (methodName.equals(m.getName()) && m.getParameterCount() == paramCount) {
                    return m;
                }
            }
        }
        return null;
    }

    // =========================================================================
    // Multi-source compilation helpers (for Property 10)
    // =========================================================================

    private static void compileMultipleWithoutProcessor(final List<String> classNames,
                                                         final List<String> sources,
                                                         final Path outputDir) throws Exception {
        compileMultiple(classNames, sources, outputDir, List.of(), List.of("-proc:none"));
    }

    private static void compileMultipleWithProcessor(final List<String> classNames,
                                                      final List<String> sources,
                                                      final Path outputDir) throws Exception {
        compileMultiple(classNames, sources, outputDir,
                List.of(new RawitAnnotationProcessor()),
                List.of("-proc:only", "-classpath", buildClasspath(outputDir)));

        final java.util.Set<String> originalNames = new java.util.HashSet<>();
        for (final String cn : classNames) {
            originalNames.add(cn.replace('.', File.separatorChar) + ".java");
        }

        final java.util.List<File> generatedJavaFiles = new java.util.ArrayList<>();
        try (final var stream = Files.walk(outputDir)) {
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
    // Property 6: Code generation produces correct staged API for records
    // Feature: record-constructor-support, Property 6: Code generation produces correct staged API for records
    // =========================================================================

    /**
     * For any record-derived AnnotatedMethod with N parameters, the JavaPoetGenerator pipeline
     * SHALL produce a Constructor caller class containing N stage interfaces with setter methods
     * matching the parameter names in order, and a ConstructStageInvoker terminal interface with
     * a construct() method returning the record type.
     *
     * <p>Validates: Requirements 5.1, 5.2, 5.3
     */
    @Property(tries = 5)
    void property6_codeGenerationProducesCorrectStagedApiForRecords(
            @ForAll("recordComponentCount") int n
    ) throws Exception {
        // Feature: record-constructor-support, Property 6: Code generation produces correct staged API for records
        final String recordName = "RecProp6_" + n + "_" + Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
        final String qualifiedName = "testpkg." + recordName;
        final String source = buildRecordSource(recordName, n);

        final Path outputDir = Files.createTempDirectory("prop6_rec_");
        try (final URLClassLoader loader = compileAndLoad(qualifiedName, source, outputDir)) {
            final Class<?> recordClass = loader.loadClass(qualifiedName);

            // 1. Verify the Constructor caller class exists
            final Class<?> constructorClass = loader.loadClass("testpkg.generated." + recordName + "Constructor");
            assertNotNull(constructorClass,
                    "A top-level '" + recordName + "Constructor' caller class must be generated for record " + recordName);
            assertTrue(Modifier.isPublic(constructorClass.getModifiers()),
                    "Constructor caller class must be public");

            // 2. Walk the staged chain to verify N stage interfaces with correct setter methods
            Object stage = recordClass.getMethod("constructor").invoke(null);

            for (int i = 0; i < n; i++) {
                final String componentName = "c" + i;

                // Verify the current stage object implements an interface with the expected setter
                final Method stageMethod = findMethod(stage, componentName, 1);
                assertNotNull(stageMethod,
                        "Stage " + (i + 1) + " must have setter method '" + componentName + "'");
                assertEquals(int.class, stageMethod.getParameterTypes()[0],
                        "Stage setter '" + componentName + "' must accept int");

                stageMethod.setAccessible(true);
                stage = stageMethod.invoke(stage, i);
            }

            // 3. Verify the terminal interface has construct() returning the record type
            final Method constructMethod = findMethod(stage, "construct", 0);
            assertNotNull(constructMethod,
                    "Terminal stage must have a 'construct()' method (ConstructStageInvoker)");
            assertEquals(recordClass, constructMethod.getReturnType(),
                    "construct() must return " + recordName);

            // 4. Verify construct() actually works
            constructMethod.setAccessible(true);
            final Object result = constructMethod.invoke(stage);
            assertNotNull(result, "construct() must return a non-null instance");
            assertInstanceOf(recordClass, result,
                    "construct() result must be an instance of " + recordName);

            // 5. Verify no invoke() method exists (this is @Constructor, not @Invoker)
            final Method invokeMethod = findMethod(stage, "invoke", 0);
            assertNull(invokeMethod,
                    "Terminal stage must NOT have an 'invoke()' method — only 'construct()'");
        } finally {
            deleteRecursively(outputDir);
        }
    }

    // =========================================================================
    // Property 11: Pipeline integration for record-derived AnnotatedMethod
    // Feature: record-constructor-support, Property 11: Pipeline integration for record-derived AnnotatedMethod
    // =========================================================================

    /**
     * For any AnnotatedMethod built from a record type, the model SHALL successfully pass
     * through OverloadGroup construction, MergeTreeBuilder.build(), and InvokerClassSpec.build()
     * without errors, producing a valid merge tree and generated source code.
     *
     * <p>We verify this by compiling a @Constructor-annotated record through the full processor
     * pipeline. Successful compilation proves the AnnotatedMethod passed through OverloadGroup,
     * MergeTreeBuilder, and InvokerClassSpec without errors.
     *
     * <p>Validates: Requirements 4.3
     */
    @Property(tries = 5)
    void property11_pipelineIntegrationForRecordDerivedAnnotatedMethod(
            @ForAll("recordComponentCount") int n
    ) throws Exception {
        // Feature: record-constructor-support, Property 11: Pipeline integration for record-derived AnnotatedMethod
        final String recordName = "RecProp11_" + n + "_" + Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
        final String qualifiedName = "testpkg." + recordName;
        final String source = buildRecordSource(recordName, n);

        final Path outputDir = Files.createTempDirectory("prop11_rec_");
        try (final URLClassLoader loader = compileAndLoad(qualifiedName, source, outputDir)) {
            final Class<?> recordClass = loader.loadClass(qualifiedName);

            // 1. Verify the Constructor caller class was generated (proves InvokerClassSpec.build() succeeded)
            final Class<?> constructorClass = loader.loadClass("testpkg.generated." + recordName + "Constructor");
            assertNotNull(constructorClass,
                    "Constructor caller class must be generated (proves pipeline integration)");

            // 2. Verify the entry point exists (proves BytecodeInjector succeeded)
            final Method entryPoint = recordClass.getMethod("constructor");
            assertNotNull(entryPoint,
                    "constructor() entry point must exist (proves bytecode injection succeeded)");

            // 3. Walk the full chain to verify the entire pipeline produced working code
            Object stage = entryPoint.invoke(null);
            for (int i = 0; i < n; i++) {
                final Method stageMethod = findMethod(stage, "c" + i, 1);
                assertNotNull(stageMethod,
                        "Stage method 'c" + i + "' must exist (proves MergeTreeBuilder produced correct tree)");
                stageMethod.setAccessible(true);
                stage = stageMethod.invoke(stage, i);
            }

            final Method constructMethod = findMethod(stage, "construct", 0);
            assertNotNull(constructMethod,
                    "construct() must exist (proves OverloadGroup → MergeTree → CodeGen pipeline worked)");

            constructMethod.setAccessible(true);
            final Object result = constructMethod.invoke(stage);
            assertNotNull(result, "construct() must return a non-null instance");
            assertInstanceOf(recordClass, result,
                    "construct() result must be an instance of " + recordName);
        } finally {
            deleteRecursively(outputDir);
        }
    }

    // =========================================================================
    // Property 9: Backward compatibility for regular class constructors
    // Feature: record-constructor-support, Property 9: Backward compatibility for regular class constructors
    // =========================================================================

    /**
     * For any regular (non-record) class constructor annotated with @Constructor, the
     * ElementValidator SHALL apply the same validation rules as before this feature, and the
     * RawitAnnotationProcessor SHALL build the AnnotatedMethod using the existing
     * ExecutableElement-based code path, producing identical output to the pre-feature behavior.
     *
     * <p>We verify this by compiling a regular class with @Constructor on its constructor and
     * checking that the staged API works identically to the pre-feature behavior: constructor()
     * entry point, stage methods matching parameter names, and construct() terminal.
     *
     * <p>Validates: Requirements 1.2, 8.1, 8.2
     */
    @Property(tries = 5)
    void property9_backwardCompatibilityForRegularClassConstructors(
            @ForAll("paramCount") int n
    ) throws Exception {
        // Feature: record-constructor-support, Property 9: Backward compatibility for regular class constructors
        final String className = "RegProp9_" + n + "_" + Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
        final String qualifiedName = "testpkg." + className;
        final String source = buildConstructorSource(className, n);

        final Path outputDir = Files.createTempDirectory("prop9_reg_");
        try (final URLClassLoader loader = compileAndLoad(qualifiedName, source, outputDir)) {
            final Class<?> cls = loader.loadClass(qualifiedName);

            // 1. Verify constructor() entry point exists (backward compatible behavior)
            final Method entryPoint = cls.getMethod("constructor");
            assertNotNull(entryPoint,
                    "Regular class " + className + " must have constructor() entry point");
            assertTrue(Modifier.isPublic(entryPoint.getModifiers()),
                    "constructor() must be public");
            assertTrue(Modifier.isStatic(entryPoint.getModifiers()),
                    "constructor() must be static");

            // 2. Walk the staged chain: constructor().p0(0).p1(0)...p(N-1)(0).construct()
            Object stage = entryPoint.invoke(null);
            for (int i = 0; i < n; i++) {
                final String paramName = "p" + i;
                final Method stageMethod = findMethod(stage, paramName, 1);
                assertNotNull(stageMethod,
                        "Stage method '" + paramName + "' must exist at position " + i);
                assertEquals(int.class, stageMethod.getParameterTypes()[0],
                        "Stage method '" + paramName + "' must accept int");
                stageMethod.setAccessible(true);
                stage = stageMethod.invoke(stage, i + 1);
            }

            // 3. Verify terminal is construct() (not invoke() — this is @Constructor)
            final Method constructMethod = findMethod(stage, "construct", 0);
            assertNotNull(constructMethod,
                    "Terminal must have 'construct()' method");
            assertEquals(cls, constructMethod.getReturnType(),
                    "construct() must return " + className);

            // 4. Invoke and verify the result
            constructMethod.setAccessible(true);
            final Object result = constructMethod.invoke(stage);
            assertNotNull(result, "construct() must return a non-null instance");
            assertInstanceOf(cls, result,
                    "construct() result must be an instance of " + className);

            // 5. Verify field values match what we passed through the chain
            for (int i = 0; i < n; i++) {
                final Object fieldValue = cls.getField("p" + i).get(result);
                assertEquals(i + 1, fieldValue,
                        "Field p" + i + " must equal " + (i + 1));
            }
        } finally {
            deleteRecursively(outputDir);
        }
    }

    // =========================================================================
    // Property 10: Independent processing of records and regular classes
    // Feature: record-constructor-support, Property 10: Independent processing of records and regular classes
    // =========================================================================

    /**
     * For any compilation round containing both a @Constructor-annotated record type and a
     * @Constructor-annotated regular class constructor, the processor SHALL produce correct
     * AnnotatedMethod models for both, and neither SHALL interfere with the other's overload
     * grouping, code generation, or bytecode injection.
     *
     * <p>Validates: Requirements 8.3
     */
    @Property(tries = 5)
    void property10_independentProcessingOfRecordsAndRegularClasses(
            @ForAll("recordComponentCount") int n
    ) throws Exception {
        // Feature: record-constructor-support, Property 10: Independent processing of records and regular classes
        final String suffix = Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);

        // Use different packages so each gets its own independent Constructor caller class
        final String recordPkg = "recpkg" + suffix;
        final String classPkg = "clspkg" + suffix;

        // Build record source with n int components
        final StringBuilder recComponents = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) recComponents.append(", ");
            recComponents.append("int c").append(i);
        }
        final String recordSource =
                "package " + recordPkg + ";\n" +
                "import rawit.Constructor;\n" +
                "@Constructor\n" +
                "public record Rec(" + recComponents + ") {}\n";

        // Build class source with n int parameters
        final StringBuilder clsFields = new StringBuilder();
        final StringBuilder clsParams = new StringBuilder();
        final StringBuilder clsAssignments = new StringBuilder();
        for (int i = 0; i < n; i++) {
            clsFields.append("    public final int p").append(i).append(";\n");
            if (i > 0) clsParams.append(", ");
            clsParams.append("int p").append(i);
            clsAssignments.append("        this.p").append(i).append(" = p").append(i).append(";\n");
        }
        final String classSource =
                "package " + classPkg + ";\n" +
                "import rawit.Constructor;\n" +
                "public class Cls {\n" +
                clsFields +
                "    @Constructor\n" +
                "    public Cls(" + clsParams + ") {\n" +
                clsAssignments +
                "    }\n" +
                "}\n";

        final Path outputDir = Files.createTempDirectory("prop10_both_");
        try (final URLClassLoader loader = compileMultipleAndLoad(
                List.of(recordPkg + ".Rec", classPkg + ".Cls"),
                List.of(recordSource, classSource),
                outputDir)) {

            // --- Verify the record's staged API ---
            final Class<?> recordClass = loader.loadClass(recordPkg + ".Rec");
            Object recStage = recordClass.getMethod("constructor").invoke(null);
            for (int i = 0; i < n; i++) {
                final Method m = findMethod(recStage, "c" + i, 1);
                assertNotNull(m, "Record stage method 'c" + i + "' must exist");
                m.setAccessible(true);
                recStage = m.invoke(recStage, i + 10);
            }
            final Method recConstruct = findMethod(recStage, "construct", 0);
            assertNotNull(recConstruct, "Record terminal must have construct()");
            recConstruct.setAccessible(true);
            final Object recResult = recConstruct.invoke(recStage);
            assertNotNull(recResult, "Record construct() must return non-null");
            assertInstanceOf(recordClass, recResult, "Record result must be correct type");
            for (int i = 0; i < n; i++) {
                assertEquals(i + 10, recordClass.getMethod("c" + i).invoke(recResult),
                        "Record component c" + i + " must equal " + (i + 10));
            }

            // --- Verify the regular class's staged API ---
            final Class<?> clsClass = loader.loadClass(classPkg + ".Cls");
            Object clsStage = clsClass.getMethod("constructor").invoke(null);
            for (int i = 0; i < n; i++) {
                final Method m = findMethod(clsStage, "p" + i, 1);
                assertNotNull(m, "Class stage method 'p" + i + "' must exist");
                m.setAccessible(true);
                clsStage = m.invoke(clsStage, i + 20);
            }
            final Method clsConstruct = findMethod(clsStage, "construct", 0);
            assertNotNull(clsConstruct, "Class terminal must have construct()");
            clsConstruct.setAccessible(true);
            final Object clsResult = clsConstruct.invoke(clsStage);
            assertNotNull(clsResult, "Class construct() must return non-null");
            assertInstanceOf(clsClass, clsResult, "Class result must be correct type");
            for (int i = 0; i < n; i++) {
                assertEquals(i + 20, clsClass.getField("p" + i).get(clsResult),
                        "Class field p" + i + " must equal " + (i + 20));
            }
        } finally {
            deleteRecursively(outputDir);
        }
    }
}
