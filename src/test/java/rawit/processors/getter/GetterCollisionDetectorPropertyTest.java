package rawit.processors.getter;

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
 * Property-based tests for {@link GetterCollisionDetector} collision detection rules.
 *
 * <p>Uses {@code javax.tools.JavaCompiler} to compile small annotated source snippets
 * with the annotation processor active ({@code -proc:only}), then asserts on the
 * emitted diagnostics.
 *
 * <p>Properties 7, 8, and 9 will pass once the processor integration (task 9.1)
 * wires {@code GetterCollisionDetector} into the {@code @Getter} processing branch.
 */
class GetterCollisionDetectorPropertyTest {

    private Path tempDir;

    @BeforeProperty
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("rawit-collision-pbt-");
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

    /**
     * Compiles multiple source files together (needed for inheritance scenarios).
     */
    private List<Diagnostic<? extends JavaFileObject>> compileMultiple(Map<String, String> sourcesByClassName) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "getSystemJavaCompiler() returned null — tests must run on a JDK, not a JRE");
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
            "int", "String", "long", "double", "float", "byte", "short", "char");

    @Provide
    Arbitrary<String> fieldTypes() {
        return Arbitraries.of(FIELD_TYPES);
    }

    @Provide
    Arbitrary<String> fieldNames() {
        return Arbitraries.of("value", "name", "count", "data", "status", "label", "title", "score");
    }

    // -------------------------------------------------------------------------
    // Property 7: Same-class method collision detection
    // -------------------------------------------------------------------------

    @Property(tries = 5)
    void property7_sameClassMethodCollisionDetected(
            @ForAll("fieldTypes") String fieldType,
            @ForAll("fieldNames") String fieldName) {
        // Feature: getter-annotation, Property 7: Same-class method collision detection
        // **Validates: Requirements 6.1**
        //
        // A class has a zero-param method with the same name as the computed getter
        // for a @Getter field. The collision detector must report an error.

        String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        String className = uniqueClassName("SameClassCollision");

        String source = """
                import rawit.Getter;
                public class %s {
                    @Getter
                    %s %s;

                    public %s %s() { return %s; }
                }
                """.formatted(className, fieldType, fieldName,
                fieldType, getterName, defaultValue(fieldType));

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        assertTrue(countErrors(diags) >= 1,
                "Expected at least 1 error for @Getter field '%s' colliding with existing method '%s()'. Diagnostics: %s"
                        .formatted(fieldName, getterName, diags));
        assertTrue(hasErrorContaining(diags, "conflicts with existing method"),
                "Expected error message containing 'conflicts with existing method'. Diagnostics: %s"
                        .formatted(diags));
    }

    @Property(tries = 5)
    void property7_noCollisionWhenMethodHasParameters(
            @ForAll("fieldTypes") String fieldType,
            @ForAll("fieldNames") String fieldName) {
        // Feature: getter-annotation, Property 7: Same-class method collision detection
        // **Validates: Requirements 6.1**
        //
        // A method with the same name but WITH parameters should NOT collide.

        String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        String className = uniqueClassName("NoCollisionWithParams");

        String source = """
                import rawit.Getter;
                public class %s {
                    @Getter
                    %s %s;

                    public %s %s(int unused) { return %s; }
                }
                """.formatted(className, fieldType, fieldName,
                fieldType, getterName, defaultValue(fieldType));

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        boolean hasCollisionError = hasErrorContaining(diags, "conflicts with existing method");
        assertFalse(hasCollisionError,
                "Expected no collision error when existing method has parameters. Diagnostics: %s"
                        .formatted(diags));
    }

    // -------------------------------------------------------------------------
    // Property 8: Inter-getter collision detection
    // -------------------------------------------------------------------------

    @Property(tries = 5)
    void property8_interGetterCollisionDetected(
            @ForAll("fieldTypes") String fieldType,
            @ForAll("fieldNames") String fieldName) {
        // Feature: getter-annotation, Property 8: Inter-getter collision detection
        // **Validates: Requirements 6.2**
        //
        // Two @Getter fields in the same class produce the same getter name.
        // Use a boolean field and a non-boolean field that both resolve to the same
        // getter name: e.g. boolean isValue and String isValue both produce getIsValue/isValue.
        // Simpler approach: two fields of the same type with the same name is impossible,
        // so we use two different field names that produce the same getter name.
        // Actually, the simplest collision: boolean field "x" → isX, and another boolean field
        // also named "x" is impossible. Instead, use two String fields that would produce
        // the same getter: not possible with different names.
        //
        // The most reliable approach: use a boolean field named e.g. "active" (getter: isActive)
        // and another boolean field named "isActive" (getter: isActive) — both produce isActive().

        String className = uniqueClassName("InterGetterCollision");

        // boolean active → isActive(), boolean isActive → isActive()
        // Both produce the same getter name "isActive"
        String source = """
                import rawit.Getter;
                public class %s {
                    @Getter
                    boolean %s;

                    @Getter
                    boolean is%s;
                }
                """.formatted(className, fieldName,
                Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1));

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        assertTrue(countErrors(diags) >= 1,
                "Expected at least 1 error for inter-getter collision. Diagnostics: %s"
                        .formatted(diags));
        assertTrue(hasErrorContaining(diags, "conflicts with another @Getter field"),
                "Expected error message containing 'conflicts with another @Getter field'. Diagnostics: %s"
                        .formatted(diags));
    }

    @Property(tries = 5)
    void property8_noCollisionWhenGetterNamesDiffer(
            @ForAll("fieldTypes") String fieldType) {
        // Feature: getter-annotation, Property 8: Inter-getter collision detection
        // **Validates: Requirements 6.2**
        //
        // Two @Getter fields with different getter names should not collide.

        String className = uniqueClassName("NoInterGetterCollision");

        String source = """
                import rawit.Getter;
                public class %s {
                    @Getter
                    %s alpha;

                    @Getter
                    %s beta;
                }
                """.formatted(className, fieldType, fieldType);

        List<Diagnostic<? extends JavaFileObject>> diags = compile(className, source);

        boolean hasCollisionError = hasErrorContaining(diags, "conflicts with another @Getter field");
        assertFalse(hasCollisionError,
                "Expected no inter-getter collision when getter names differ. Diagnostics: %s"
                        .formatted(diags));
    }

    // -------------------------------------------------------------------------
    // Property 9: Inherited method collision detection
    // -------------------------------------------------------------------------

    @Property(tries = 5)
    void property9_inheritedMethodCollisionDetected(
            @ForAll("fieldTypes") String fieldType,
            @ForAll("fieldNames") String fieldName) {
        // Feature: getter-annotation, Property 9: Inherited method collision detection
        // **Validates: Requirements 12.1**
        //
        // A superclass has a zero-param method, and a derived class has a @Getter field
        // whose computed getter name matches that method. The collision detector must
        // report an error.

        String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        String baseName = uniqueClassName("InheritedBase");
        String derivedName = uniqueClassName("InheritedDerived");

        String baseSource = """
                public class %s {
                    public %s %s() { return %s; }
                }
                """.formatted(baseName, fieldType, getterName, defaultValue(fieldType));

        String derivedSource = """
                import rawit.Getter;
                public class %s extends %s {
                    @Getter
                    %s %s;
                }
                """.formatted(derivedName, baseName, fieldType, fieldName);

        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(baseName, baseSource);
        sources.put(derivedName, derivedSource);

        List<Diagnostic<? extends JavaFileObject>> diags = compileMultiple(sources);

        assertTrue(countErrors(diags) >= 1,
                "Expected at least 1 error for @Getter field '%s' colliding with inherited method '%s()' from %s. Diagnostics: %s"
                        .formatted(fieldName, getterName, baseName, diags));
        assertTrue(hasErrorContaining(diags, "conflicts with inherited method"),
                "Expected error message containing 'conflicts with inherited method'. Diagnostics: %s"
                        .formatted(diags));
    }

    @Property(tries = 5)
    void property9_noCollisionWhenInheritedMethodHasParameters(
            @ForAll("fieldTypes") String fieldType,
            @ForAll("fieldNames") String fieldName) {
        // Feature: getter-annotation, Property 9: Inherited method collision detection
        // **Validates: Requirements 12.1**
        //
        // An inherited method with the same name but WITH parameters should NOT collide.

        String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        String baseName = uniqueClassName("InheritedBaseParams");
        String derivedName = uniqueClassName("InheritedDerivedParams");

        String baseSource = """
                public class %s {
                    public %s %s(int unused) { return %s; }
                }
                """.formatted(baseName, fieldType, getterName, defaultValue(fieldType));

        String derivedSource = """
                import rawit.Getter;
                public class %s extends %s {
                    @Getter
                    %s %s;
                }
                """.formatted(derivedName, baseName, fieldType, fieldName);

        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(baseName, baseSource);
        sources.put(derivedName, derivedSource);

        List<Diagnostic<? extends JavaFileObject>> diags = compileMultiple(sources);

        boolean hasCollisionError = hasErrorContaining(diags, "conflicts with inherited method");
        assertFalse(hasCollisionError,
                "Expected no inherited collision when inherited method has parameters. Diagnostics: %s"
                        .formatted(diags));
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /** Returns a default literal value for the given Java type (for compiling method bodies). */
    private static String defaultValue(String type) {
        return switch (type) {
            case "int", "byte", "short" -> "0";
            case "long" -> "0L";
            case "float" -> "0.0f";
            case "double" -> "0.0";
            case "char" -> "'a'";
            case "boolean" -> "false";
            default -> "null"; // reference types like String
        };
    }
}
