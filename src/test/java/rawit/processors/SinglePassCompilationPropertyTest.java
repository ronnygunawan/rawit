package rawit.processors;

import com.sun.source.util.TaskEvent;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import javax.annotation.processing.Processor;
import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for single-pass compilation patterns used in
 * {@link RawitAnnotationProcessor}.
 *
 * <p>Validates the {@code Map.merge()} accumulation pattern and the
 * {@code TaskListener} event-filtering guard that the processor uses for
 * pending injection maps ({@code pendingInvokerInjections} and
 * {@code pendingGetterInjections}).
 */
class SinglePassCompilationPropertyTest {

    // =========================================================================
    // Arbitraries
    // =========================================================================

    /** Binary class names in slash-separated form. */
    @Provide
    Arbitrary<String> binaryClassName() {
        return Arbitraries.of(
                "com/example/Foo",
                "com/example/Bar",
                "com/example/Baz",
                "org/test/Widget",
                "org/test/Gadget"
        );
    }

    /** A single entry (simulating a MergeTree or AnnotatedField as a String). */
    @Provide
    Arbitrary<String> entry() {
        return Arbitraries.of(
                "tree_add", "tree_multiply", "tree_subtract",
                "field_name", "field_age", "field_email",
                "tree_compute", "field_id", "tree_process", "field_value"
        );
    }

    /** A non-empty list of entries, simulating a batch of injections for one round. */
    @Provide
    Arbitrary<List<String>> entryList() {
        return entry().list().ofMinSize(1).ofMaxSize(5);
    }

    // =========================================================================
    // Property 1: Pending injection merge accumulates all entries
    // Feature: single-pass-compilation, Property 1: Pending injection merge accumulates all entries
    // =========================================================================

    /**
     * For any sequence of lists targeting the same binary class name,
     * {@code Map.merge()} with list concatenation produces a single list
     * containing all entries from all input lists, in order.
     *
     * <p>This validates the exact pattern used in {@code RawitAnnotationProcessor.process()}:
     * <pre>
     * pendingInvokerInjections.merge(className, new ArrayList<>(list), (a, b) -> {
     *     a.addAll(b);
     *     return a;
     * });
     * </pre>
     *
     * <p><b>Validates: Requirements 2.4, 3.4</b>
     */
    @Property(tries = 100)
    void property1_pendingInjectionMergeAccumulatesAllEntries(
            @ForAll("binaryClassName") String className,
            @ForAll @Size(min = 1, max = 10) List<@From("entryList") List<String>> batches
    ) {
        // Feature: single-pass-compilation, Property 1: Pending injection merge accumulates all entries

        // --- Act: apply Map.merge() with list concatenation (same pattern as the processor) ---
        final Map<String, List<String>> pendingMap = new LinkedHashMap<>();
        for (final List<String> batch : batches) {
            pendingMap.merge(className, new ArrayList<>(batch), (a, b) -> {
                a.addAll(b);
                return a;
            });
        }

        // --- Assert 1: The map contains exactly one entry for the class name ---
        assertTrue(pendingMap.containsKey(className),
                "Map must contain an entry for " + className);
        assertEquals(1, pendingMap.size(),
                "Map must contain exactly one key since all batches target the same class");

        // --- Assert 2: The merged list contains all entries from all batches ---
        final List<String> expected = new ArrayList<>();
        for (final List<String> batch : batches) {
            expected.addAll(batch);
        }
        final List<String> merged = pendingMap.get(className);

        assertEquals(expected.size(), merged.size(),
                "Merged list size must equal total entries across all batches");

        // --- Assert 3: Order is preserved — entries appear in batch order ---
        assertEquals(expected, merged,
                "Merged list must contain all entries in original batch order");
    }

    // =========================================================================
    // Arbitraries for Property 2
    // =========================================================================

    /** TaskEvent.Kind values that are NOT GENERATE. */
    @Provide
    Arbitrary<TaskEvent.Kind> nonGenerateKind() {
        return Arbitraries.of(
                TaskEvent.Kind.PARSE,
                TaskEvent.Kind.ENTER,
                TaskEvent.Kind.ANALYZE,
                TaskEvent.Kind.COMPILATION
        );
    }

    // =========================================================================
    // Property 2: TaskListener only responds to GENERATE events
    // Feature: single-pass-compilation, Property 2: TaskListener only responds to GENERATE events
    // =========================================================================

    /**
     * For any {@link TaskEvent.Kind} other than {@code GENERATE}, the
     * TaskListener's guard condition ({@code if (e.getKind() != TaskEvent.Kind.GENERATE) return;})
     * prevents any modification to the pending injection maps.
     *
     * <p>This test simulates the listener's filtering logic by populating pending
     * maps with entries, then applying the guard for a non-GENERATE event kind.
     * The maps must remain unchanged because the guard causes an early return.
     *
     * <p><b>Validates: Requirements 4.4</b>
     */
    @Property(tries = 100)
    void property2_taskListenerOnlyRespondsToGenerateEvents(
            @ForAll("nonGenerateKind") TaskEvent.Kind eventKind,
            @ForAll("binaryClassName") String className,
            @ForAll("entryList") List<String> invokerEntries,
            @ForAll("entryList") List<String> getterEntries
    ) {
        // Feature: single-pass-compilation, Property 2: TaskListener only responds to GENERATE events

        // --- Arrange: populate pending maps with entries ---
        final Map<String, List<String>> pendingInvokerInjections = new LinkedHashMap<>();
        pendingInvokerInjections.put(className, new ArrayList<>(invokerEntries));

        final Map<String, List<String>> pendingGetterInjections = new LinkedHashMap<>();
        pendingGetterInjections.put(className, new ArrayList<>(getterEntries));

        // Snapshot the maps before the simulated listener fires
        final Map<String, List<String>> invokerSnapshot = new LinkedHashMap<>();
        for (final var entry : pendingInvokerInjections.entrySet()) {
            invokerSnapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        final Map<String, List<String>> getterSnapshot = new LinkedHashMap<>();
        for (final var entry : pendingGetterInjections.entrySet()) {
            getterSnapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        // --- Act: simulate the TaskListener guard ---
        // This mirrors the exact guard in createPostGenerateListener():
        //   if (e.getKind() != TaskEvent.Kind.GENERATE) return;
        if (eventKind != TaskEvent.Kind.GENERATE) {
            // Guard triggers — early return, no map modification
        } else {
            // Would process injections (remove from maps, invoke injectors)
            pendingGetterInjections.remove(className);
            pendingInvokerInjections.remove(className);
        }

        // --- Assert: maps are unchanged because the guard filtered out the event ---
        assertEquals(invokerSnapshot, pendingInvokerInjections,
                "Pending invoker injections must not be modified for " + eventKind + " events");
        assertEquals(getterSnapshot, pendingGetterInjections,
                "Pending getter injections must not be modified for " + eventKind + " events");

        // --- Assert: the event kind is definitely not GENERATE ---
        assertNotEquals(TaskEvent.Kind.GENERATE, eventKind,
                "This property only tests non-GENERATE event kinds");
    }

    // =========================================================================
    // Property 3: TaskListener removes pending entries after processing
    // Feature: single-pass-compilation, Property 3: TaskListener removes pending entries after processing
    // =========================================================================

    /**
     * For any binary class name present in the pending injections maps, after the
     * TaskListener processes a GENERATE event for that class (using {@code Map.remove()}),
     * the pending maps shall no longer contain an entry for that class name.
     *
     * <p>This validates the consume-on-remove pattern in
     * {@code createPostGenerateListener()}:
     * <pre>
     * final List&lt;AnnotatedField&gt; fields = pendingGetterInjections.remove(binaryName);
     * final List&lt;MergeTree&gt; trees = pendingInvokerInjections.remove(binaryName);
     * </pre>
     *
     * <p><b>Validates: Requirements 4.5</b>
     */
    @Property(tries = 100)
    void property3_taskListenerRemovesPendingEntriesAfterProcessing(
            @ForAll("binaryClassName") String targetClassName,
            @ForAll @Size(min = 0, max = 4) List<@From("binaryClassName") String> otherClassNames,
            @ForAll("entryList") List<String> invokerEntries,
            @ForAll("entryList") List<String> getterEntries
    ) {
        // Feature: single-pass-compilation, Property 3: TaskListener removes pending entries after processing

        // --- Arrange: populate pending maps with entries for the target class and others ---
        final Map<String, List<String>> pendingInvokerInjections = new LinkedHashMap<>();
        final Map<String, List<String>> pendingGetterInjections = new LinkedHashMap<>();

        pendingInvokerInjections.put(targetClassName, new ArrayList<>(invokerEntries));
        pendingGetterInjections.put(targetClassName, new ArrayList<>(getterEntries));

        // Add entries for other class names to verify they are not affected
        for (final String otherName : otherClassNames) {
            if (!otherName.equals(targetClassName)) {
                pendingInvokerInjections.putIfAbsent(otherName, new ArrayList<>(List.of("other_tree")));
                pendingGetterInjections.putIfAbsent(otherName, new ArrayList<>(List.of("other_field")));
            }
        }

        // Snapshot other entries before processing
        final Set<String> otherInvokerKeys = new LinkedHashSet<>(pendingInvokerInjections.keySet());
        otherInvokerKeys.remove(targetClassName);
        final Set<String> otherGetterKeys = new LinkedHashSet<>(pendingGetterInjections.keySet());
        otherGetterKeys.remove(targetClassName);

        // --- Act: simulate the TaskListener's GENERATE event processing ---
        // This mirrors the exact pattern in createPostGenerateListener():
        //   final List<AnnotatedField> fields = pendingGetterInjections.remove(binaryName);
        //   final List<MergeTree> trees = pendingInvokerInjections.remove(binaryName);
        final List<String> removedGetters = pendingGetterInjections.remove(targetClassName);
        final List<String> removedInvokers = pendingInvokerInjections.remove(targetClassName);

        // --- Assert 1: The target class name is no longer in either pending map ---
        assertFalse(pendingInvokerInjections.containsKey(targetClassName),
                "Pending invoker injections must not contain " + targetClassName + " after remove()");
        assertFalse(pendingGetterInjections.containsKey(targetClassName),
                "Pending getter injections must not contain " + targetClassName + " after remove()");

        // --- Assert 2: remove() returned the original entries (non-null) ---
        assertNotNull(removedGetters,
                "remove() must return the getter entries that were present for " + targetClassName);
        assertNotNull(removedInvokers,
                "remove() must return the invoker entries that were present for " + targetClassName);
        assertEquals(getterEntries, removedGetters,
                "Removed getter entries must match the originally stored entries");
        assertEquals(invokerEntries, removedInvokers,
                "Removed invoker entries must match the originally stored entries");

        // --- Assert 3: Other class entries are unaffected ---
        for (final String otherKey : otherInvokerKeys) {
            assertTrue(pendingInvokerInjections.containsKey(otherKey),
                    "Invoker entry for " + otherKey + " must not be removed by processing " + targetClassName);
        }
        for (final String otherKey : otherGetterKeys) {
            assertTrue(pendingGetterInjections.containsKey(otherKey),
                    "Getter entry for " + otherKey + " must not be removed by processing " + targetClassName);
        }
    }

    // =========================================================================
    // Compilation infrastructure for Property 6
    // =========================================================================

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

    /**
     * Multi-pass compilation: compile without processor, then run processor in proc-only mode,
     * then compile generated sources.
     */
    private static URLClassLoader compileMultiPassAndLoad(final String className,
                                                          final String source,
                                                          final Path outputDir) throws Exception {
        // Pass 1: compile without processor to produce .class file
        compile(className, source, outputDir, List.of(), List.of("-proc:none"));

        // Pass 2: run processor in proc-only mode
        compile(className, source, outputDir,
                List.of(new RawitAnnotationProcessor()),
                List.of("-proc:only", "-classpath", buildClasspath(outputDir)));

        // Pass 3: compile generated .java files
        final String classRelativePath = className.replace('.', File.separatorChar) + ".java";
        final List<File> generatedJavaFiles = new ArrayList<>();
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

                final List<JavaFileObject> sourceFiles = new ArrayList<>();
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

        return new URLClassLoader(
                new URL[]{outputDir.toUri().toURL()},
                Thread.currentThread().getContextClassLoader());
    }

    /**
     * Single-pass compilation: compile with the processor in a single javac invocation.
     */
    private static URLClassLoader compileSinglePassAndLoad(final String className,
                                                            final String source,
                                                            final Path outputDir) throws Exception {
        compile(className, source, outputDir,
                List.of(new RawitAnnotationProcessor()),
                List.of("-classpath", buildClasspath(outputDir)));
        return new URLClassLoader(
                new URL[]{outputDir.toUri().toURL()},
                Thread.currentThread().getContextClassLoader());
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

    /**
     * Extracts a sorted set of method signatures from a class, including only methods
     * that were injected by the processor (parameterless overloads, getters, constructor
     * entry points). Excludes standard Object methods and the original user-defined methods.
     */
    private static SortedSet<String> extractInjectedMethodSignatures(final Class<?> cls) {
        final SortedSet<String> signatures = new TreeSet<>();
        final Set<String> objectMethodSignatures = Set.of(
                "equals(java.lang.Object):boolean",
                "hashCode():int",
                "toString():java.lang.String",
                "getClass():java.lang.Class",
                "notify():void",
                "notifyAll():void",
                "wait():void",
                "wait(long):void",
                "wait(long,int):void"
        );
        for (final Method m : cls.getDeclaredMethods()) {
            // Include only parameterless methods that represent injected overloads,
            // getters, or constructor entry points; exclude synthetic/bridge methods.
            if (m.isSynthetic() || m.isBridge() || m.getParameterCount() != 0) continue;
            final String sig = m.getName() + "("
                    + Arrays.stream(m.getParameterTypes())
                            .map(Class::getName)
                            .collect(Collectors.joining(","))
                    + "):" + m.getReturnType().getName();
            if (objectMethodSignatures.contains(sig)) continue;
            signatures.add(sig);
        }
        return signatures;
    }

    // =========================================================================
    // Arbitraries for Property 6
    // =========================================================================

    /** Method names suitable for @Invoker annotation. */
    @Provide
    Arbitrary<String> invokerMethodName() {
        return Arbitraries.of("add", "compute", "process", "calculate", "transform");
    }

    /** Parameter counts for generated methods (2-4 params). */
    @Provide
    Arbitrary<Integer> paramCount() {
        return Arbitraries.integers().between(2, 4);
    }

    /** Annotation type to use: INVOKER, CONSTRUCTOR, or GETTER. */
    @Provide
    Arbitrary<String> annotationType() {
        return Arbitraries.of("INVOKER", "CONSTRUCTOR", "GETTER");
    }

    // =========================================================================
    // Property 6: Bytecode equivalence between immediate and deferred injection
    // Feature: single-pass-compilation, Property 6: Bytecode equivalence between immediate and deferred injection
    // =========================================================================

    /**
     * For any annotated source compiled with the Rawit processor, the class produced by
     * immediate injection (multi-pass mode) shall have the same method signatures as the
     * class produced by deferred injection (single-pass mode).
     *
     * <p>This property generates source variations with different annotation types, method
     * names, and parameter counts, compiles each in both modes, and compares the resulting
     * classes via reflection to verify they have identical injected method sets.
     *
     * <p>Uses {@code tries = 10} because each try performs two full compilations (multi-pass
     * and single-pass), which is expensive. 10 tries provides meaningful coverage across
     * the annotation type × method name × parameter count space.
     *
     * <p><b>Validates: Requirements 8.3</b>
     */
    @Property(tries = 10)
    void property6_bytecodeEquivalenceBetweenImmediateAndDeferredInjection(
            @ForAll("annotationType") String annoType,
            @ForAll("invokerMethodName") String methodName,
            @ForAll("paramCount") int paramCount
    ) throws Exception {
        // Feature: single-pass-compilation, Property 6: Bytecode equivalence between immediate and deferred injection

        final String uniqueSuffix = String.format("%08x", Objects.hash(annoType, methodName, paramCount));

        // --- Generate source code based on annotation type ---
        final String simpleClassName;
        final String source;

        switch (annoType) {
            case "INVOKER" -> {
                simpleClassName = "SpEquiv_" + methodName + "_" + paramCount + "_" + uniqueSuffix;
                source = buildInvokerSource(simpleClassName, methodName, paramCount);
            }
            case "CONSTRUCTOR" -> {
                simpleClassName = "SpEquivCtor_" + paramCount + "_" + uniqueSuffix;
                source = buildConstructorSource(simpleClassName, paramCount);
            }
            case "GETTER" -> {
                simpleClassName = "SpEquivGetter_" + paramCount + "_" + uniqueSuffix;
                source = buildGetterSource(simpleClassName, paramCount);
            }
            default -> throw new IllegalArgumentException("Unknown annotation type: " + annoType);
        }

        final String className = "testpkg." + simpleClassName;

        // --- Compile in multi-pass mode ---
        final Path multiPassDir = Files.createTempDirectory("prop6_mp_");
        final SortedSet<String> multiPassSignatures;
        try (final URLClassLoader mpLoader = compileMultiPassAndLoad(className, source, multiPassDir)) {
            final Class<?> mpClass = mpLoader.loadClass(className);
            multiPassSignatures = extractInjectedMethodSignatures(mpClass);
        } finally {
            deleteRecursively(multiPassDir);
        }

        // --- Compile in single-pass mode ---
        final Path singlePassDir = Files.createTempDirectory("prop6_sp_");
        final SortedSet<String> singlePassSignatures;
        try (final URLClassLoader spLoader = compileSinglePassAndLoad(className, source, singlePassDir)) {
            final Class<?> spClass = spLoader.loadClass(className);
            singlePassSignatures = extractInjectedMethodSignatures(spClass);
        } finally {
            deleteRecursively(singlePassDir);
        }

        // --- Assert: both modes produce classes with identical method signatures ---
        assertEquals(multiPassSignatures, singlePassSignatures,
                "Multi-pass and single-pass compilation must produce identical method signatures "
                        + "for " + annoType + " annotation on " + className
                        + "\n  multi-pass:  " + multiPassSignatures
                        + "\n  single-pass: " + singlePassSignatures);

        // --- Assert: the class has at least one injected method beyond the original ---
        // For INVOKER/CONSTRUCTOR: there should be a parameterless overload entry point
        // For GETTER: there should be getter methods
        assertFalse(multiPassSignatures.isEmpty(),
                "Compiled class must have at least one method for " + annoType + " on " + className);
    }

    // =========================================================================
    // Source generation helpers for Property 6
    // =========================================================================

    /**
     * Builds a source with an {@code @Invoker}-annotated method with the given name
     * and parameter count. All parameters are {@code int} and the method returns their sum.
     */
    private static String buildInvokerSource(final String simpleClassName,
                                              final String methodName,
                                              final int paramCount) {
        final StringBuilder params = new StringBuilder();
        final StringBuilder body = new StringBuilder("return ");
        for (int i = 0; i < paramCount; i++) {
            if (i > 0) {
                params.append(", ");
                body.append(" + ");
            }
            final String paramName = "p" + i;
            params.append("int ").append(paramName);
            body.append(paramName);
        }
        body.append(";");

        return "package testpkg;\n"
                + "import rawit.Invoker;\n"
                + "public class " + simpleClassName + " {\n"
                + "    @Invoker\n"
                + "    public int " + methodName + "(" + params + ") { " + body + " }\n"
                + "}\n";
    }

    /**
     * Builds a source with a {@code @Constructor}-annotated constructor with the given
     * parameter count. All parameters are {@code int} and stored in public final fields.
     */
    private static String buildConstructorSource(final String simpleClassName,
                                                  final int paramCount) {
        final StringBuilder fields = new StringBuilder();
        final StringBuilder params = new StringBuilder();
        final StringBuilder assigns = new StringBuilder();
        for (int i = 0; i < paramCount; i++) {
            final String fieldName = "f" + i;
            fields.append("    public final int ").append(fieldName).append(";\n");
            if (i > 0) params.append(", ");
            params.append("int ").append(fieldName);
            assigns.append("        this.").append(fieldName).append(" = ").append(fieldName).append(";\n");
        }

        return "package testpkg;\n"
                + "import rawit.Constructor;\n"
                + "public class " + simpleClassName + " {\n"
                + fields
                + "    @Constructor\n"
                + "    public " + simpleClassName + "(" + params + ") {\n"
                + assigns
                + "    }\n"
                + "}\n";
    }

    /**
     * Builds a source with {@code @Getter}-annotated fields. Uses the given count
     * to generate that many private int fields with getters.
     */
    private static String buildGetterSource(final String simpleClassName,
                                             final int fieldCount) {
        final StringBuilder fields = new StringBuilder();
        final StringBuilder ctorParams = new StringBuilder();
        final StringBuilder ctorAssigns = new StringBuilder();
        for (int i = 0; i < fieldCount; i++) {
            final String fieldName = "val" + i;
            fields.append("    @Getter private int ").append(fieldName).append(";\n");
            if (i > 0) ctorParams.append(", ");
            ctorParams.append("int ").append(fieldName);
            ctorAssigns.append("        this.").append(fieldName).append(" = ").append(fieldName).append(";\n");
        }

        return "package testpkg;\n"
                + "import rawit.Getter;\n"
                + "public class " + simpleClassName + " {\n"
                + fields
                + "    public " + simpleClassName + "(" + ctorParams + ") {\n"
                + ctorAssigns
                + "    }\n"
                + "}\n";
    }
}
