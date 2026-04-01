package rg.rawit.processors.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ElementValidator} validation rules.
 *
 * <p>Uses {@code javax.tools.JavaCompiler} to compile small annotated source snippets
 * with the annotation processor active, then asserts on the emitted diagnostics.
 *
 * <p>Each test covers exactly one validation rule.
 */
class ElementValidatorTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Compiles the given source snippet (as a named compilation unit) with the
     * {@code RawitAnnotationProcessor} on the processor path, and returns all
     * diagnostics emitted.
     */
    private List<Diagnostic<? extends JavaFileObject>> compile(String className, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "javax.tools.JavaCompiler not available — run tests with a JDK, not a JRE");

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();

        JavaFileObject sourceFile = new SimpleJavaFileObject(
                URI.create("string:///" + className.replace('.', '/') + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };

        // Output compiled classes to the temp directory
        List<String> options = Arrays.asList(
                "-d", tempDir.toString(),
                "-proc:only",          // run annotation processing only (no class output needed)
                "-processor", "rg.rawit.processors.RawitAnnotationProcessor",
                "-classpath", System.getProperty("java.class.path")
        );

        try (StandardJavaFileManager fm = compiler.getStandardFileManager(collector, Locale.ROOT, null)) {
            StringWriter out = new StringWriter();
            JavaCompiler.CompilationTask task = compiler.getTask(
                    out, fm, collector, options, null, List.of(sourceFile));
            task.call();
        } catch (IOException e) {
            throw new RuntimeException("Failed to close file manager", e);
        }

        return collector.getDiagnostics();
    }

    /** Returns only ERROR diagnostics from the list. */
    private List<Diagnostic<? extends JavaFileObject>> errors(
            List<Diagnostic<? extends JavaFileObject>> diags) {
        List<Diagnostic<? extends JavaFileObject>> result = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diags) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                result.add(d);
            }
        }
        return result;
    }

    /** Returns true if any error message contains the given substring. */
    private boolean hasErrorContaining(List<Diagnostic<? extends JavaFileObject>> diags,
                                       String substring) {
        for (Diagnostic<? extends JavaFileObject> d : errors(diags)) {
            if (d.getMessage(Locale.ROOT).contains(substring)) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // Requirement 1.1 — @Curry on zero-param method → ERROR
    // =========================================================================

    @Test
    void curry_zeroParamMethod_emitsError() {
        // Req 1.1: @Curry on a method with zero parameters must produce an ERROR
        String source = """
                import rg.rawit.Curry;
                public class ZeroParamMethod {
                    @Curry
                    public void noArgs() {}
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("ZeroParamMethod", source);

        assertTrue(hasErrorContaining(diags, "at least one parameter"),
                "Expected error about requiring at least one parameter, got: " + errors(diags));
    }

    // =========================================================================
    // Requirement 1.2 — @Curry on private method → ERROR
    // =========================================================================

    @Test
    void curry_privateMethod_emitsError() {
        // Req 1.2: @Curry on a private method must produce an ERROR
        String source = """
                import rg.rawit.Curry;
                public class PrivateMethod {
                    @Curry
                    private void secret(int x) {}
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("PrivateMethod", source);

        assertTrue(hasErrorContaining(diags, "package-private visibility"),
                "Expected error about visibility, got: " + errors(diags));
    }

    // =========================================================================
    // Requirement 2.1 — @Curry on zero-param constructor → ERROR
    // =========================================================================

    @Test
    void curry_zeroParamConstructor_emitsError() {
        // Req 2.1: @Curry on a constructor with zero parameters must produce an ERROR
        String source = """
                import rg.rawit.Curry;
                public class ZeroParamCtor {
                    @Curry
                    public ZeroParamCtor() {}
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("ZeroParamCtor", source);

        assertTrue(hasErrorContaining(diags, "at least one parameter"),
                "Expected error about requiring at least one parameter, got: " + errors(diags));
    }

    // =========================================================================
    // Requirement 2.2 — @Curry on private constructor → ERROR
    // =========================================================================

    @Test
    void curry_privateConstructor_emitsError() {
        // Req 2.2: @Curry on a private constructor must produce an ERROR
        String source = """
                import rg.rawit.Curry;
                public class PrivateCtor {
                    @Curry
                    private PrivateCtor(int x) {}
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("PrivateCtor", source);

        assertTrue(hasErrorContaining(diags, "package-private visibility"),
                "Expected error about visibility, got: " + errors(diags));
    }

    // =========================================================================
    // Requirement 15.1 — @Constructor on non-constructor element → ERROR
    // =========================================================================

    @Test
    void constructorAnnotation_onMethod_emitsError() {
        // Req 15.1: @Constructor may only target constructors; placing it on a method is invalid.
        // Note: @Constructor has @Target(CONSTRUCTOR) so javac itself will reject it before the
        // processor runs. We verify that the compilation fails with an error.
        String source = """
                import rg.rawit.Constructor;
                public class ConstructorOnMethod {
                    @Constructor
                    public void notACtor(int x) {}
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("ConstructorOnMethod", source);

        // javac rejects @Constructor on a METHOD because @Target(CONSTRUCTOR) — this is an error
        assertFalse(errors(diags).isEmpty(),
                "Expected at least one error when @Constructor is placed on a method");
    }

    // =========================================================================
    // Requirement 15.2 — @Constructor on zero-param constructor → ERROR
    // =========================================================================

    @Test
    void constructorAnnotation_zeroParamConstructor_emitsError() {
        // Req 15.2: @Constructor on a constructor with zero parameters must produce an ERROR
        String source = """
                import rg.rawit.Constructor;
                public class ZeroParamConstructorAnnotation {
                    @Constructor
                    public ZeroParamConstructorAnnotation() {}
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("ZeroParamConstructorAnnotation", source);

        assertTrue(hasErrorContaining(diags, "at least one parameter"),
                "Expected error about requiring at least one parameter, got: " + errors(diags));
    }

    // =========================================================================
    // Requirement 15.3 — @Constructor on private constructor → ERROR
    // =========================================================================

    @Test
    void constructorAnnotation_privateConstructor_emitsError() {
        // Req 15.3: @Constructor on a private constructor must produce an ERROR
        String source = """
                import rg.rawit.Constructor;
                public class PrivateConstructorAnnotation {
                    @Constructor
                    private PrivateConstructorAnnotation(int x) {}
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("PrivateConstructorAnnotation", source);

        assertTrue(hasErrorContaining(diags, "package-private visibility"),
                "Expected error about visibility, got: " + errors(diags));
    }

    // =========================================================================
    // Conflict detection — existing zero-param overload with same name → ERROR
    // =========================================================================

    @Test
    void curry_conflictingZeroParamOverload_emitsError() {
        // Req 3.7 / 13.1: If a zero-param method with the same name already exists,
        // @Curry must emit an ERROR about the naming conflict.
        String source = """
                import rg.rawit.Curry;
                public class ConflictingOverload {
                    // This zero-param method conflicts with what @Curry would inject
                    public ConflictingOverload bar() { return this; }

                    @Curry
                    public int bar(int x) { return x; }
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("ConflictingOverload", source);

        assertTrue(hasErrorContaining(diags, "already exists"),
                "Expected error about existing parameterless overload, got: " + errors(diags));
    }

    // =========================================================================
    // Happy path — valid @Curry method produces no errors
    // =========================================================================

    @Test
    void curry_validMethod_noErrors() {
        // Req 1.3: A valid @Curry method (non-private, ≥1 param) must produce no errors
        String source = """
                import rg.rawit.Curry;
                public class ValidCurryMethod {
                    @Curry
                    public int add(int x, int y) { return x + y; }
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("ValidCurryMethod", source);

        assertTrue(errors(diags).isEmpty(),
                "Expected no errors for a valid @Curry method, got: " + errors(diags));
    }

    // =========================================================================
    // Happy path — valid @Constructor produces no errors
    // =========================================================================

    @Test
    void constructorAnnotation_validConstructor_noErrors() {
        // Req 15.4: A valid @Constructor (non-private, ≥1 param) must produce no errors
        String source = """
                import rg.rawit.Constructor;
                public class ValidConstructorAnnotation {
                    @Constructor
                    public ValidConstructorAnnotation(int id, String name) {}
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("ValidConstructorAnnotation", source);

        assertTrue(errors(diags).isEmpty(),
                "Expected no errors for a valid @Constructor, got: " + errors(diags));
    }
}
