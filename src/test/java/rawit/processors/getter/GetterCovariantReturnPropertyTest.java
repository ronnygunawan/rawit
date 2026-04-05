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
 * Property-based tests for covariant return type validation in field-hiding scenarios.
 *
 * <p>Uses {@code javax.tools.JavaCompiler} to compile small annotated source snippets
 * with the annotation processor active ({@code -proc:only}), then asserts on the
 * emitted diagnostics.
 *
 * <p>Property 10 will pass once task 9.1 integrates the full collision detection
 * into the processor.
 */
class GetterCovariantReturnPropertyTest {

    private Path tempDir;

    @BeforeProperty
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("rawit-covariant-pbt-");
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

    /** Covariant type pairs: (baseType, derivedType) where derivedType IS a subtype of baseType. */
    @Provide
    Arbitrary<String[]> covariantTypePairs() {
        return Arbitraries.of(
                new String[]{"Number", "Integer"},
                new String[]{"Number", "Double"},
                new String[]{"Number", "Long"},
                new String[]{"Object", "String"},
                new String[]{"Object", "Integer"},
                new String[]{"CharSequence", "String"}
        );
    }

    /** Incompatible type pairs: (baseType, derivedType) where derivedType is NOT a subtype of baseType. */
    @Provide
    Arbitrary<String[]> incompatibleTypePairs() {
        return Arbitraries.of(
                new String[]{"String", "Integer"},
                new String[]{"Integer", "String"},
                new String[]{"Double", "Long"},
                new String[]{"String", "Number"},
                new String[]{"Integer", "Double"}
        );
    }

    @Provide
    Arbitrary<String> fieldNames() {
        return Arbitraries.of("value", "data", "item", "result", "payload");
    }

    // -------------------------------------------------------------------------
    // Property 10: Covariant return type validation in field hiding
    // -------------------------------------------------------------------------

    @Property(tries = 5)
    void property10_covariantReturnTypeAccepted(
            @ForAll("covariantTypePairs") String[] typePair,
            @ForAll("fieldNames") String fieldName) {
        // Feature: getter-annotation, Property 10: Covariant return type validation in field hiding
        // **Validates: Requirements 8.6, 8.7**
        //
        // Base class has @Getter <baseType> field, derived class has @Getter <derivedType> field
        // where derivedType IS a subtype of baseType. This should be accepted (covariant return
        // type is valid).

        String baseType = typePair[0];
        String derivedType = typePair[1];
        String baseName = uniqueClassName("CovariantBase");
        String derivedName = uniqueClassName("CovariantDerived");

        String baseSource = """
                import rawit.Getter;
                public class %s {
                    @Getter
                    %s %s;
                }
                """.formatted(baseName, baseType, fieldName);

        String derivedSource = """
                import rawit.Getter;
                public class %s extends %s {
                    @Getter
                    %s %s;
                }
                """.formatted(derivedName, baseName, derivedType, fieldName);

        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(baseName, baseSource);
        sources.put(derivedName, derivedSource);

        List<Diagnostic<? extends JavaFileObject>> diags = compileMultiple(sources);

        boolean hasIncompatibleError = hasErrorContaining(diags, "incompatible return types");
        assertFalse(hasIncompatibleError,
                "Expected no 'incompatible return types' error when derived type %s is a subtype of base type %s. Diagnostics: %s"
                        .formatted(derivedType, baseType, diags));
    }

    @Property(tries = 5)
    void property10_incompatibleReturnTypeRejected(
            @ForAll("incompatibleTypePairs") String[] typePair,
            @ForAll("fieldNames") String fieldName) {
        // Feature: getter-annotation, Property 10: Covariant return type validation in field hiding
        // **Validates: Requirements 8.6, 8.7**
        //
        // Base class has @Getter <baseType> field, derived class has @Getter <derivedType> field
        // where derivedType is NOT a subtype of baseType. The processor must emit an error
        // containing "incompatible return types".

        String baseType = typePair[0];
        String derivedType = typePair[1];
        String baseName = uniqueClassName("IncompatBase");
        String derivedName = uniqueClassName("IncompatDerived");

        String baseSource = """
                import rawit.Getter;
                public class %s {
                    @Getter
                    %s %s;
                }
                """.formatted(baseName, baseType, fieldName);

        String derivedSource = """
                import rawit.Getter;
                public class %s extends %s {
                    @Getter
                    %s %s;
                }
                """.formatted(derivedName, baseName, derivedType, fieldName);

        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(baseName, baseSource);
        sources.put(derivedName, derivedSource);

        List<Diagnostic<? extends JavaFileObject>> diags = compileMultiple(sources);

        assertTrue(countErrors(diags) >= 1,
                "Expected at least 1 error for incompatible return types (%s vs %s). Diagnostics: %s"
                        .formatted(derivedType, baseType, diags));
        assertTrue(hasErrorContaining(diags, "incompatible return types"),
                "Expected error message containing 'incompatible return types'. Diagnostics: %s"
                        .formatted(diags));
    }

    @Property(tries = 5)
    void property10_sameTypeFieldHidingAccepted(
            @ForAll("fieldNames") String fieldName) {
        // Feature: getter-annotation, Property 10: Covariant return type validation in field hiding
        // **Validates: Requirements 8.6, 8.7**
        //
        // Base class has @Getter Object field, derived class has @Getter Object field
        // (same type). This should be accepted — same type is trivially covariant.

        String baseName = uniqueClassName("SameTypeBase");
        String derivedName = uniqueClassName("SameTypeDerived");

        String baseSource = """
                import rawit.Getter;
                public class %s {
                    @Getter
                    Object %s;
                }
                """.formatted(baseName, fieldName);

        String derivedSource = """
                import rawit.Getter;
                public class %s extends %s {
                    @Getter
                    Object %s;
                }
                """.formatted(derivedName, baseName, fieldName);

        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(baseName, baseSource);
        sources.put(derivedName, derivedSource);

        List<Diagnostic<? extends JavaFileObject>> diags = compileMultiple(sources);

        boolean hasIncompatibleError = hasErrorContaining(diags, "incompatible return types");
        assertFalse(hasIncompatibleError,
                "Expected no 'incompatible return types' error when both fields have the same type. Diagnostics: %s"
                        .formatted(diags));
    }
}
