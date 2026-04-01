package rawit.processors.validation;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import net.jqwik.api.lifecycle.*;

import javax.tools.*;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Property-based tests for {@link ElementValidator} validation rules.
 *
 * <p>Uses {@code javax.tools.JavaCompiler} to compile small annotated source snippets
 * with the annotation processor active, then asserts on the emitted diagnostics.
 */
class ElementValidatorPropertyTest {

    // jqwik does not support JUnit 5 @TempDir injection; manage temp dir manually.
    private Path tempDir;

    @BeforeProperty
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("rawit-pbt-");
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

    private static final List<String> PARAM_TYPES = List.of("int", "String", "long", "boolean", "double");
    private static final List<String> VISIBILITIES = List.of("public", "protected", "");

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<Diagnostic<? extends JavaFileObject>> compile(String className, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        StandardJavaFileManager fm = compiler.getStandardFileManager(collector, Locale.ROOT, null);

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

        compiler.getTask(new StringWriter(), fm, collector, options, null, List.of(sourceFile)).call();
        return collector.getDiagnostics();
    }

    private long countErrors(List<Diagnostic<? extends JavaFileObject>> diags) {
        return diags.stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).count();
    }

    private String uniqueClassName(String prefix) {
        return prefix + CLASS_COUNTER.incrementAndGet();
    }

    /** Builds a comma-separated parameter list like "int p0, String p1, long p2". */
    private String buildParams(List<String> types) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(types.get(i)).append(" p").append(i);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Arbitraries
    // -------------------------------------------------------------------------

    @Provide
    Arbitrary<String> validMethodNames() {
        return Arbitraries.of("foo", "bar", "baz", "compute", "process", "handle", "execute", "run");
    }

    @Provide
    Arbitrary<List<String>> paramTypeLists() {
        // 1 to 5 parameters, each chosen from PARAM_TYPES
        return Arbitraries.of(PARAM_TYPES)
                .list()
                .ofMinSize(1)
                .ofMaxSize(5);
    }

    @Provide
    Arbitrary<String> validVisibilities() {
        return Arbitraries.of(VISIBILITIES);
    }

    // -------------------------------------------------------------------------
    // Property 1: Valid element produces no errors
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void property1_validCurryMethod_producesNoErrors(
            @ForAll("validMethodNames") String methodName,
            @ForAll("paramTypeLists") List<String> paramTypes,
            @ForAll("validVisibilities") String visibility) {
        // Feature: project-rawit-curry, Property 1: Valid element produces no errors
        // Validates: Requirements 1.3, 2.3, 15.4

        String className = uniqueClassName("ValidCurryMethod");
        String visibilityPrefix = visibility.isEmpty() ? "" : visibility + " ";
        String params = buildParams(paramTypes);

        String source = """
                import rawit.Curry;
                public class %s {
                    @Curry
                    %sint method_%s(%s) { return; }
                }
                """.formatted(className, visibilityPrefix, methodName, params);

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        long errorCount = countErrors(diags);
        assertEquals(0, errorCount,
                "Expected 0 errors for valid @Curry method '%s' with visibility '%s' and %d params, got %d. Diagnostics: %s"
                .formatted(methodName, visibility, paramTypes.size(), errorCount, diags));
    }

    @Property(tries = 100)
    void property1_validCurryConstructor_producesNoErrors(
            @ForAll("paramTypeLists") List<String> paramTypes,
            @ForAll("validVisibilities") String visibility) {
        // Feature: project-rawit-curry, Property 1: Valid element produces no errors
        // Validates: Requirements 2.3, 15.4

        String className = uniqueClassName("ValidCurryCtor");
        String visibilityPrefix = visibility.isEmpty() ? "" : visibility + " ";
        String params = buildParams(paramTypes);

        String source = """
                import rawit.Curry;
                public class %s {
                    @Curry
                    %s%s(%s) {}
                }
                """.formatted(className, visibilityPrefix, className, params);

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        long errorCount = countErrors(diags);
        assertEquals(0, errorCount,
                "Expected 0 errors for valid @Curry constructor with visibility '%s' and %d params, got %d. Diagnostics: %s"
                .formatted(visibility, paramTypes.size(), errorCount, diags));
    }

    @Property(tries = 100)
    void property1_validConstructorAnnotation_producesNoErrors(
            @ForAll("paramTypeLists") List<String> paramTypes,
            @ForAll("validVisibilities") String visibility) {
        // Feature: project-rawit-curry, Property 1: Valid element produces no errors
        // Validates: Requirements 15.4

        String className = uniqueClassName("ValidConstructorAnnotation");
        String visibilityPrefix = visibility.isEmpty() ? "" : visibility + " ";
        String params = buildParams(paramTypes);

        String source = """
                import rawit.Constructor;
                public class %s {
                    @Constructor
                    %s%s(%s) {}
                }
                """.formatted(className, visibilityPrefix, className, params);

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        long errorCount = countErrors(diags);
        assertEquals(0, errorCount,
                "Expected 0 errors for valid @Constructor with visibility '%s' and %d params, got %d. Diagnostics: %s"
                .formatted(visibility, paramTypes.size(), errorCount, diags));
    }

    // -------------------------------------------------------------------------
    // Property 20: Exactly one error per violated validation rule
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void property20_curryZeroParams_exactlyOneError(
            @ForAll("validMethodNames") String methodName,
            @ForAll("validVisibilities") String visibility) {
        // Feature: project-rawit-curry, Property 20: Exactly one error per violated validation rule
        // Validates: Requirements 10.2
        //
        // Note: a zero-param @Curry method triggers both the "zero params" rule AND the
        // conflict-detection rule (the method itself is a zero-param overload with the same name),
        // so the validator emits exactly 2 errors. We assert >= 1 to confirm that violations
        // always produce errors, and separately verify the count is 2 (both rules fire).

        String className = uniqueClassName("ZeroParamCurry");
        String visibilityPrefix = visibility.isEmpty() ? "" : visibility + " ";

        // Violations: zero params + self-conflict (both fire simultaneously)
        String source = """
                import rawit.Curry;
                public class %s {
                    @Curry
                    %svoid %s() {}
                }
                """.formatted(className, visibilityPrefix, methodName);

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        long errorCount = countErrors(diags);
        // The validator checks all rules without short-circuiting:
        // rule 1: zero params → 1 error
        // rule 2: conflict (method itself is a zero-param overload with same name) → 1 error
        // Total: exactly 2 errors for this specific combination
        assertEquals(2, errorCount,
                "Expected exactly 2 errors for @Curry zero-param method (zero-params + self-conflict), got %d. Diagnostics: %s"
                .formatted(errorCount, diags));
    }

    @Property(tries = 100)
    void property20_curryPrivateMethod_exactlyOneError(
            @ForAll("validMethodNames") String methodName,
            @ForAll("paramTypeLists") List<String> paramTypes) {
        // Feature: project-rawit-curry, Property 20: Exactly one error per violated validation rule
        // Validates: Requirements 10.2

        String className = uniqueClassName("PrivateCurryMethod");
        String params = buildParams(paramTypes);

        // Single violation: private visibility (param count is valid)
        String source = """
                import rawit.Curry;
                public class %s {
                    @Curry
                    private void %s(%s) {}
                }
                """.formatted(className, methodName, params);

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        long errorCount = countErrors(diags);
        assertEquals(1, errorCount,
                "Expected exactly 1 error for private @Curry method with %d params, got %d. Diagnostics: %s"
                .formatted(paramTypes.size(), errorCount, diags));
    }

    @Property(tries = 100)
    void property20_curryZeroParamsConstructor_exactlyOneError(
            @ForAll("validVisibilities") String visibility) {
        // Feature: project-rawit-curry, Property 20: Exactly one error per violated validation rule
        // Validates: Requirements 10.2

        String className = uniqueClassName("ZeroParamCurryCtor");
        String visibilityPrefix = visibility.isEmpty() ? "" : visibility + " ";

        // Single violation: zero params on constructor
        String source = """
                import rawit.Curry;
                public class %s {
                    @Curry
                    %s%s() {}
                }
                """.formatted(className, visibilityPrefix, className);

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        long errorCount = countErrors(diags);
        assertEquals(1, errorCount,
                "Expected exactly 1 error for @Curry constructor with zero params (visibility='%s'), got %d. Diagnostics: %s"
                .formatted(visibility, errorCount, diags));
    }

    @Property(tries = 100)
    void property20_curryPrivateConstructor_exactlyOneError(
            @ForAll("paramTypeLists") List<String> paramTypes) {
        // Feature: project-rawit-curry, Property 20: Exactly one error per violated validation rule
        // Validates: Requirements 10.2

        String className = uniqueClassName("PrivateCurryCtor");
        String params = buildParams(paramTypes);

        // Single violation: private constructor
        String source = """
                import rawit.Curry;
                public class %s {
                    @Curry
                    private %s(%s) {}
                }
                """.formatted(className, className, params);

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        long errorCount = countErrors(diags);
        assertEquals(1, errorCount,
                "Expected exactly 1 error for private @Curry constructor with %d params, got %d. Diagnostics: %s"
                .formatted(paramTypes.size(), errorCount, diags));
    }

    @Property(tries = 100)
    void property20_constructorAnnotationZeroParams_exactlyOneError(
            @ForAll("validVisibilities") String visibility) {
        // Feature: project-rawit-curry, Property 20: Exactly one error per violated validation rule
        // Validates: Requirements 10.2

        String className = uniqueClassName("ZeroParamConstructorAnnotation");
        String visibilityPrefix = visibility.isEmpty() ? "" : visibility + " ";

        // Single violation: zero params on @Constructor
        String source = """
                import rawit.Constructor;
                public class %s {
                    @Constructor
                    %s%s() {}
                }
                """.formatted(className, visibilityPrefix, className);

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        long errorCount = countErrors(diags);
        assertEquals(1, errorCount,
                "Expected exactly 1 error for @Constructor with zero params (visibility='%s'), got %d. Diagnostics: %s"
                .formatted(visibility, errorCount, diags));
    }

    @Property(tries = 100)
    void property20_constructorAnnotationPrivate_exactlyOneError(
            @ForAll("paramTypeLists") List<String> paramTypes) {
        // Feature: project-rawit-curry, Property 20: Exactly one error per violated validation rule
        // Validates: Requirements 10.2

        String className = uniqueClassName("PrivateConstructorAnnotation");
        String params = buildParams(paramTypes);

        // Single violation: private @Constructor
        String source = """
                import rawit.Constructor;
                public class %s {
                    @Constructor
                    private %s(%s) {}
                }
                """.formatted(className, className, params);

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        long errorCount = countErrors(diags);
        assertEquals(1, errorCount,
                "Expected exactly 1 error for private @Constructor with %d params, got %d. Diagnostics: %s"
                .formatted(paramTypes.size(), errorCount, diags));
    }
}
