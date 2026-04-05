package rawit.processors.getter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.*;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GetterCollisionDetector} collision detection rules.
 *
 * <p>Uses {@code javax.tools.JavaCompiler} with {@code -proc:only} and the
 * {@code RawitAnnotationProcessor} to compile small annotated source snippets,
 * then asserts on the emitted diagnostics.
 *
 * <p>Requirements: 6.1, 6.2, 12.1
 */
class GetterCollisionDetectorTest {

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
            compiler.getTask(new StringWriter(), fm, collector, options, null, List.of(sourceFile)).call();
        } catch (IOException e) {
            throw new RuntimeException("Failed to close file manager", e);
        }

        return collector.getDiagnostics();
    }

    /**
     * Compiles multiple source files together (needed for inheritance scenarios).
     */
    private List<Diagnostic<? extends JavaFileObject>> compileMultiple(Map<String, String> sourcesByClassName) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "javax.tools.JavaCompiler not available — run tests with a JDK, not a JRE");

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();

        List<JavaFileObject> sourceFiles = new ArrayList<>();
        for (Map.Entry<String, String> entry : sourcesByClassName.entrySet()) {
            String className = entry.getKey();
            String source = entry.getValue();
            sourceFiles.add(new SimpleJavaFileObject(
                    URI.create("string:///" + className.replace('.', '/') + ".java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            });
        }

        List<String> options = Arrays.asList(
                "-d", tempDir.toString(),
                "-proc:only",
                "-processor", "rawit.processors.RawitAnnotationProcessor",
                "-classpath", System.getProperty("java.class.path")
        );

        try (StandardJavaFileManager fm = compiler.getStandardFileManager(collector, Locale.ROOT, null)) {
            compiler.getTask(new StringWriter(), fm, collector, options, null, sourceFiles).call();
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
    // Requirement 6.1 — Same-class method collision: existing getName() + @Getter String name → error
    // =========================================================================

    @Test
    void sameClassCollision_existingGetNameMethod_andGetterStringName_emitsError() {
        // Req 6.1: existing zero-param method getName() + @Getter String name → error
        String source = """
                import rawit.Getter;
                public class SameClassCollisionTest {
                    @Getter
                    String name;

                    public String getName() { return "manual"; }
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("SameClassCollisionTest", source);

        assertFalse(errors(diags).isEmpty(),
                "Expected at least one error for @Getter field 'name' colliding with existing getName()");
        assertTrue(hasErrorContaining(diags, "conflicts with existing method"),
                "Expected error message containing 'conflicts with existing method', got: " + errors(diags));
    }

    @Test
    void sameClassCollision_existingIsActiveMethod_andGetterBooleanActive_emitsError() {
        // Req 6.1: existing zero-param method isActive() + @Getter boolean active → error
        String source = """
                import rawit.Getter;
                public class BoolCollisionTest {
                    @Getter
                    boolean active;

                    public boolean isActive() { return false; }
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("BoolCollisionTest", source);

        assertFalse(errors(diags).isEmpty(),
                "Expected at least one error for @Getter boolean 'active' colliding with existing isActive()");
        assertTrue(hasErrorContaining(diags, "conflicts with existing method"),
                "Expected error message containing 'conflicts with existing method', got: " + errors(diags));
    }

    @Test
    void sameClassCollision_methodWithParameters_noError() {
        // Req 6.1: a method with the same name but WITH parameters should NOT collide
        String source = """
                import rawit.Getter;
                public class NoCollisionParamsTest {
                    @Getter
                    String name;

                    public String getName(int index) { return "manual"; }
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("NoCollisionParamsTest", source);

        assertFalse(hasErrorContaining(diags, "conflicts with existing method"),
                "Expected no collision error when existing method has parameters, got: " + errors(diags));
    }

    // =========================================================================
    // Requirement 6.2 — Inter-getter collision: two fields both producing getName → error
    // =========================================================================

    @Test
    void interGetterCollision_twoBooleanFieldsProducingSameGetter_emitsError() {
        // Req 6.2: boolean active → isActive(), boolean isActive → isActive() — collision
        String source = """
                import rawit.Getter;
                public class InterGetterCollisionTest {
                    @Getter
                    boolean active;

                    @Getter
                    boolean isActive;
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("InterGetterCollisionTest", source);

        assertFalse(errors(diags).isEmpty(),
                "Expected at least one error for inter-getter collision (both produce isActive)");
        assertTrue(hasErrorContaining(diags, "conflicts with another @Getter field"),
                "Expected error message containing 'conflicts with another @Getter field', got: " + errors(diags));
    }

    @Test
    void interGetterCollision_twoFieldsWithDifferentGetterNames_noError() {
        // Req 6.2: two @Getter fields with different getter names should not collide
        String source = """
                import rawit.Getter;
                public class NoInterCollisionTest {
                    @Getter
                    String firstName;

                    @Getter
                    String lastName;
                }
                """;

        List<Diagnostic<? extends JavaFileObject>> diags = compile("NoInterCollisionTest", source);

        assertFalse(hasErrorContaining(diags, "conflicts with another @Getter field"),
                "Expected no inter-getter collision when getter names differ, got: " + errors(diags));
    }

    // =========================================================================
    // Requirement 12.1 — Inherited method collision: inherited getName() + @Getter String name → error
    // =========================================================================

    @Test
    void inheritedCollision_superclassGetNameMethod_andDerivedGetterStringName_emitsError() {
        // Req 12.1: superclass has getName(), derived class has @Getter String name → error
        String baseSource = """
                public class InheritedBase {
                    public String getName() { return "base"; }
                }
                """;

        String derivedSource = """
                import rawit.Getter;
                public class InheritedDerived extends InheritedBase {
                    @Getter
                    String name;
                }
                """;

        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("InheritedBase", baseSource);
        sources.put("InheritedDerived", derivedSource);

        List<Diagnostic<? extends JavaFileObject>> diags = compileMultiple(sources);

        assertFalse(errors(diags).isEmpty(),
                "Expected at least one error for @Getter field 'name' colliding with inherited getName()");
        assertTrue(hasErrorContaining(diags, "conflicts with inherited method"),
                "Expected error message containing 'conflicts with inherited method', got: " + errors(diags));
    }

    @Test
    void inheritedCollision_superclassMethodWithParams_noError() {
        // Req 12.1: inherited method with parameters should NOT collide
        String baseSource = """
                public class InheritedBaseParams {
                    public String getName(int index) { return "base"; }
                }
                """;

        String derivedSource = """
                import rawit.Getter;
                public class InheritedDerivedParams extends InheritedBaseParams {
                    @Getter
                    String name;
                }
                """;

        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("InheritedBaseParams", baseSource);
        sources.put("InheritedDerivedParams", derivedSource);

        List<Diagnostic<? extends JavaFileObject>> diags = compileMultiple(sources);

        assertFalse(hasErrorContaining(diags, "conflicts with inherited method"),
                "Expected no inherited collision when inherited method has parameters, got: " + errors(diags));
    }

    @Test
    void inheritedCollision_grandparentMethod_emitsError() {
        // Req 12.1: collision detection should walk the full superclass chain
        String grandparentSource = """
                public class GrandparentClass {
                    public int getCount() { return 0; }
                }
                """;

        String parentSource = """
                public class ParentClass extends GrandparentClass {
                }
                """;

        String childSource = """
                import rawit.Getter;
                public class ChildClass extends ParentClass {
                    @Getter
                    int count;
                }
                """;

        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("GrandparentClass", grandparentSource);
        sources.put("ParentClass", parentSource);
        sources.put("ChildClass", childSource);

        List<Diagnostic<? extends JavaFileObject>> diags = compileMultiple(sources);

        assertFalse(errors(diags).isEmpty(),
                "Expected at least one error for @Getter field 'count' colliding with grandparent getCount()");
        assertTrue(hasErrorContaining(diags, "conflicts with inherited method"),
                "Expected error message containing 'conflicts with inherited method', got: " + errors(diags));
    }
}
