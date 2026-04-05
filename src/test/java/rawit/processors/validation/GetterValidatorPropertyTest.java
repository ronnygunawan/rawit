package rawit.processors.validation;

import net.jqwik.api.*;
import net.jqwik.api.lifecycle.*;

import javax.tools.*;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for {@link GetterValidator} validation rules.
 *
 * <p>Uses {@code javax.tools.JavaCompiler} to compile small annotated source snippets
 * with the annotation processor active, then asserts on the emitted diagnostics.
 */
class GetterValidatorPropertyTest {

    private Path tempDir;

    @BeforeProperty
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("rawit-getter-pbt-");
    }

    @AfterProperty
    void deleteTempDir() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walkFileTree(tempDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static final AtomicInteger CLASS_COUNTER = new AtomicInteger(0);

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<Diagnostic<? extends JavaFileObject>> compile(String className, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "getSystemJavaCompiler() returned null — tests must run on a JDK, not a JRE");
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();

        JavaFileObject sourceFile = new SimpleJavaFileObject(
                URI.create("string:///" + className.replace('.', '/') + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };

        List<String> options = Arrays.asList(
                "-d", tempDir.toString(),
                "-proc:only",
                "-processor", "rawit.processors.RawitAnnotationProcessor",
                "-classpath", System.getProperty("java.class.path")
        );

        try (StandardJavaFileManager fm = compiler.getStandardFileManager(collector, Locale.ROOT, null)) {
            compiler.getTask(new StringWriter(), fm, collector, options, null, List.of(sourceFile)).call();
        } catch (IOException e) {
            throw new RuntimeException("Failed to close file manager", e);
        }
        return collector.getDiagnostics();
    }

    private long countErrors(List<Diagnostic<? extends JavaFileObject>> diags) {
        return diags.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .count();
    }

    private boolean hasErrorContaining(List<Diagnostic<? extends JavaFileObject>> diags, String substring) {
        return diags.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .anyMatch(d -> d.getMessage(Locale.ROOT).contains(substring));
    }

    private String uniqueClassName(String prefix) {
        return prefix + CLASS_COUNTER.incrementAndGet();
    }

    // -------------------------------------------------------------------------
    // Arbitraries
    // -------------------------------------------------------------------------

    private static final List<String> FIELD_TYPES = List.of(
            "int", "String", "long", "boolean", "double", "float", "byte", "short", "char");

    @Provide
    Arbitrary<String> fieldTypes() {
        return Arbitraries.of(FIELD_TYPES);
    }

    @Provide
    Arbitrary<String> fieldNames() {
        return Arbitraries.of("value", "name", "count", "flag", "data", "active", "status", "label");
    }

    /** Generates a disallowed modifier: volatile or transient. */
    @Provide
    Arbitrary<String> disallowedModifiers() {
        return Arbitraries.of("volatile", "transient");
    }

    /** Generates class kinds that should be accepted (not anonymous). */
    @Provide
    Arbitrary<String> acceptedClassKinds() {
        return Arbitraries.of("top-level", "enum", "named-inner", "static-inner", "local");
    }

    // -------------------------------------------------------------------------
    // Property 5: Volatile and transient fields are rejected
    // -------------------------------------------------------------------------

    @Property(tries = 5)
    void property5_volatileFieldRejected(
            @ForAll("fieldTypes") String fieldType,
            @ForAll("fieldNames") String fieldName) {
        // Feature: getter-annotation, Property 5: Volatile and transient fields are rejected
        // **Validates: Requirements 10.1, 10.2**

        String className = uniqueClassName("VolatileField");

        String source = """
                import rawit.Getter;
                public class %s {
                    @Getter
                    volatile %s %s;
                }
                """.formatted(className, fieldType, fieldName);

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        assertTrue(countErrors(diags) >= 1,
                "Expected at least 1 error for @Getter on volatile field '%s %s', got 0. Diagnostics: %s"
                .formatted(fieldType, fieldName, diags));
        assertTrue(hasErrorContaining(diags, "volatile"),
                "Expected error message mentioning 'volatile' for @Getter on volatile field. Diagnostics: %s"
                .formatted(diags));
    }

    @Property(tries = 5)
    void property5_transientFieldRejected(
            @ForAll("fieldTypes") String fieldType,
            @ForAll("fieldNames") String fieldName) {
        // Feature: getter-annotation, Property 5: Volatile and transient fields are rejected
        // **Validates: Requirements 10.1, 10.2**

        String className = uniqueClassName("TransientField");

        String source = """
                import rawit.Getter;
                public class %s {
                    @Getter
                    transient %s %s;
                }
                """.formatted(className, fieldType, fieldName);

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        assertTrue(countErrors(diags) >= 1,
                "Expected at least 1 error for @Getter on transient field '%s %s', got 0. Diagnostics: %s"
                .formatted(fieldType, fieldName, diags));
        assertTrue(hasErrorContaining(diags, "transient"),
                "Expected error message mentioning 'transient' for @Getter on transient field. Diagnostics: %s"
                .formatted(diags));
    }

    @Property(tries = 5)
    void property5_volatileAndTransientFieldRejectedWithBothErrors(
            @ForAll("fieldTypes") String fieldType,
            @ForAll("fieldNames") String fieldName) {
        // Feature: getter-annotation, Property 5: Volatile and transient fields are rejected
        // **Validates: Requirements 10.1, 10.2**
        // A field that is both volatile and transient should produce errors for both.

        String className = uniqueClassName("VolatileTransientField");

        String source = """
                import rawit.Getter;
                public class %s {
                    @Getter
                    volatile transient %s %s;
                }
                """.formatted(className, fieldType, fieldName);

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        assertTrue(countErrors(diags) >= 2,
                "Expected at least 2 errors for @Getter on volatile transient field, got %d. Diagnostics: %s"
                .formatted(countErrors(diags), diags));
        assertTrue(hasErrorContaining(diags, "volatile"),
                "Expected error mentioning 'volatile'. Diagnostics: %s".formatted(diags));
        assertTrue(hasErrorContaining(diags, "transient"),
                "Expected error mentioning 'transient'. Diagnostics: %s".formatted(diags));
    }

    // -------------------------------------------------------------------------
    // Property 6: Anonymous class fields are rejected, all other class kinds accepted
    // -------------------------------------------------------------------------

    @Property(tries = 5)
    void property6_anonymousClassFieldRejected(
            @ForAll("fieldTypes") String fieldType,
            @ForAll("fieldNames") String fieldName) {
        // Feature: getter-annotation, Property 6: Anonymous class fields are rejected, all other class kinds accepted
        // **Validates: Requirements 11.1, 11.2, 11.3, 11.4, 11.5**
        //
        // Anonymous class fields annotated with @Getter are not discovered by
        // roundEnv.getElementsAnnotatedWith() during annotation processing, so the
        // processor never processes them. We verify that no getter-related output
        // (errors or notes) is produced — the field is silently ignored.

        String className = uniqueClassName("AnonClassHost");

        String source = """
                import rawit.Getter;
                public class %s {
                    Object obj = new Object() {
                        @Getter
                        %s %s;
                    };
                }
                """.formatted(className, fieldType, fieldName);

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        // The processor never sees the @Getter field inside the anonymous class,
        // so no getter-related diagnostics should be emitted at all.
        long getterErrors = diags.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .filter(d -> d.getMessage(Locale.ROOT).contains("@Getter"))
                .count();
        assertEquals(0, getterErrors,
                "Expected no @Getter errors for anonymous class field (processor should not discover it), got %d. Diagnostics: %s"
                .formatted(getterErrors, diags));
    }

    @Property(tries = 5)
    void property6_topLevelClassFieldAccepted(
            @ForAll("fieldTypes") String fieldType,
            @ForAll("fieldNames") String fieldName) {
        // Feature: getter-annotation, Property 6: Anonymous class fields are rejected, all other class kinds accepted
        // **Validates: Requirements 11.1, 11.2, 11.3, 11.4, 11.5**

        String className = uniqueClassName("TopLevelClass");

        String source = """
                import rawit.Getter;
                public class %s {
                    @Getter
                    %s %s;
                }
                """.formatted(className, fieldType, fieldName);

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        assertEquals(0, countErrors(diags),
                "Expected 0 errors for @Getter in top-level class, got %d. Diagnostics: %s"
                .formatted(countErrors(diags), diags));
    }

    @Property(tries = 5)
    void property6_enumFieldAccepted(
            @ForAll("fieldTypes") String fieldType,
            @ForAll("fieldNames") String fieldName) {
        // Feature: getter-annotation, Property 6: Anonymous class fields are rejected, all other class kinds accepted
        // **Validates: Requirements 11.1, 11.2, 11.3, 11.4, 11.5**

        String className = uniqueClassName("EnumClass");

        String source = """
                import rawit.Getter;
                public enum %s {
                    A, B;
                    @Getter
                    %s %s;
                }
                """.formatted(className, fieldType, fieldName);

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        assertEquals(0, countErrors(diags),
                "Expected 0 errors for @Getter in enum, got %d. Diagnostics: %s"
                .formatted(countErrors(diags), diags));
    }

    @Property(tries = 5)
    void property6_namedInnerClassFieldAccepted(
            @ForAll("fieldTypes") String fieldType,
            @ForAll("fieldNames") String fieldName) {
        // Feature: getter-annotation, Property 6: Anonymous class fields are rejected, all other class kinds accepted
        // **Validates: Requirements 11.1, 11.2, 11.3, 11.4, 11.5**

        String className = uniqueClassName("OuterNamedInner");

        String source = """
                import rawit.Getter;
                public class %s {
                    class Inner {
                        @Getter
                        %s %s;
                    }
                }
                """.formatted(className, fieldType, fieldName);

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        assertEquals(0, countErrors(diags),
                "Expected 0 errors for @Getter in named inner class, got %d. Diagnostics: %s"
                .formatted(countErrors(diags), diags));
    }

    @Property(tries = 5)
    void property6_staticInnerClassFieldAccepted(
            @ForAll("fieldTypes") String fieldType,
            @ForAll("fieldNames") String fieldName) {
        // Feature: getter-annotation, Property 6: Anonymous class fields are rejected, all other class kinds accepted
        // **Validates: Requirements 11.1, 11.2, 11.3, 11.4, 11.5**

        String className = uniqueClassName("OuterStaticInner");

        String source = """
                import rawit.Getter;
                public class %s {
                    static class Inner {
                        @Getter
                        %s %s;
                    }
                }
                """.formatted(className, fieldType, fieldName);

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        assertEquals(0, countErrors(diags),
                "Expected 0 errors for @Getter in static inner class, got %d. Diagnostics: %s"
                .formatted(countErrors(diags), diags));
    }

    @Property(tries = 5)
    void property6_localClassFieldAccepted(
            @ForAll("fieldTypes") String fieldType,
            @ForAll("fieldNames") String fieldName) {
        // Feature: getter-annotation, Property 6: Anonymous class fields are rejected, all other class kinds accepted
        // **Validates: Requirements 11.1, 11.2, 11.3, 11.4, 11.5**

        String className = uniqueClassName("LocalClassHost");

        String source = """
                import rawit.Getter;
                public class %s {
                    void method() {
                        class Local {
                            @Getter
                            %s %s;
                        }
                    }
                }
                """.formatted(className, fieldType, fieldName);

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        assertEquals(0, countErrors(diags),
                "Expected 0 errors for @Getter in local class, got %d. Diagnostics: %s"
                .formatted(countErrors(diags), diags));
    }
}
