package rawit.processors.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.*;
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
 * Unit tests for {@link GetterValidator} validation rules.
 *
 * <p>Uses {@code javax.tools.JavaCompiler} to compile small annotated source snippets
 * with the annotation processor active ({@code -proc:only}), then asserts on the
 * emitted diagnostics.
 *
 * <p>Requirements: 10.1, 10.2, 11.1, 11.2, 11.3, 11.4, 11.5
 */
class GetterValidatorTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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

        List<String> options = Arrays.asList(
                "-d", tempDir.toString(),
                "-proc:only",
                "-processor", "rawit.processors.RawitAnnotationProcessor",
                "-classpath", System.getProperty("java.class.path")
        );

        try (StandardJavaFileManager fm = compiler.getStandardFileManager(collector, Locale.ROOT, null)) {
            StringWriter out = new StringWriter();
            compiler.getTask(out, fm, collector, options, null, List.of(sourceFile)).call();
        } catch (IOException e) {
            throw new RuntimeException("Failed to close file manager", e);
        }

        return collector.getDiagnostics();
    }

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
    // Requirement 10.1 — @Getter on volatile field → ERROR
    // =========================================================================

    @Test
    void getter_volatileField_emitsError() {
        // Req 10.1: @Getter on a volatile field must produce an ERROR
        String source = """
                import rawit.Getter;
                public class VolatileFieldTest {
                    @Getter
                    volatile int count;
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("VolatileFieldTest", source);

        assertFalse(errors(diags).isEmpty(),
                "Expected at least one error for @Getter on volatile field, got none");
        assertTrue(hasErrorContaining(diags, "volatile"),
                "Expected error message mentioning 'volatile', got: " + errors(diags));
    }

    // =========================================================================
    // Requirement 10.2 — @Getter on transient field → ERROR
    // =========================================================================

    @Test
    void getter_transientField_emitsError() {
        // Req 10.2: @Getter on a transient field must produce an ERROR
        String source = """
                import rawit.Getter;
                public class TransientFieldTest {
                    @Getter
                    transient String label;
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("TransientFieldTest", source);

        assertFalse(errors(diags).isEmpty(),
                "Expected at least one error for @Getter on transient field, got none");
        assertTrue(hasErrorContaining(diags, "transient"),
                "Expected error message mentioning 'transient', got: " + errors(diags));
    }

    // =========================================================================
    // Requirement 11.1 — @Getter inside anonymous class → silently ignored
    // =========================================================================

    @Test
    void getter_anonymousClassField_notDiscoveredByProcessor() {
        // Req 11.1: @Getter on a field inside an anonymous class is not discovered
        // by the annotation processor. This is a javac limitation:
        // roundEnv.getElementsAnnotatedWith() does not include annotations on fields
        // inside anonymous classes, so the processor never processes them. The
        // GetterValidator's anonymous class check exists as a safety net but is not
        // exercised in practice through this code path.
        String source = """
                import rawit.Getter;
                public class AnonClassTest {
                    Object obj = new Object() {
                        @Getter
                        int value;
                    };
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("AnonClassTest", source);

        long getterErrors = diags.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .filter(d -> d.getMessage(Locale.ROOT).contains("@Getter"))
                .count();
        assertEquals(0, getterErrors,
                "Expected no @Getter errors for anonymous class field, got: " + errors(diags));
    }

    // =========================================================================
    // Requirement 11.2 — @Getter inside enum → accepted
    // =========================================================================

    @Test
    void getter_enumField_accepted() {
        // Req 11.2: @Getter on a field inside an enum must produce no errors
        String source = """
                import rawit.Getter;
                public enum EnumFieldTest {
                    A, B;
                    @Getter
                    int value;
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("EnumFieldTest", source);

        assertTrue(errors(diags).isEmpty(),
                "Expected no errors for @Getter in enum, got: " + errors(diags));
    }

    // =========================================================================
    // Requirement 11.3 — @Getter inside named inner class → accepted
    // =========================================================================

    @Test
    void getter_namedInnerClassField_accepted() {
        // Req 11.3: @Getter on a field inside a named inner class must produce no errors
        String source = """
                import rawit.Getter;
                public class OuterNamedInnerTest {
                    class Inner {
                        @Getter
                        String name;
                    }
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("OuterNamedInnerTest", source);

        assertTrue(errors(diags).isEmpty(),
                "Expected no errors for @Getter in named inner class, got: " + errors(diags));
    }

    // =========================================================================
    // Requirement 11.4 — @Getter inside local class → accepted
    // =========================================================================

    @Test
    void getter_localClassField_accepted() {
        // Req 11.4: @Getter on a field inside a local class must produce no errors
        String source = """
                import rawit.Getter;
                public class LocalClassTest {
                    void method() {
                        class Local {
                            @Getter
                            double ratio;
                        }
                    }
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("LocalClassTest", source);

        assertTrue(errors(diags).isEmpty(),
                "Expected no errors for @Getter in local class, got: " + errors(diags));
    }

    // =========================================================================
    // Requirement 11.5 — @Getter inside static inner class → accepted
    // =========================================================================

    @Test
    void getter_staticInnerClassField_accepted() {
        // Req 11.5: @Getter on a field inside a static inner class must produce no errors
        String source = """
                import rawit.Getter;
                public class OuterStaticInnerTest {
                    static class Inner {
                        @Getter
                        long timestamp;
                    }
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("OuterStaticInnerTest", source);

        assertTrue(errors(diags).isEmpty(),
                "Expected no errors for @Getter in static inner class, got: " + errors(diags));
    }
}
