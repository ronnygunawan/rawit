package rawit.processors.codegen;

import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for {@link JavaPoetGenerator#resolvePackageName(String)}.
 *
 * <p>Feature: generated-class-naming, Property 3: JavaPoetGenerator package correctness
 *
 * <p>Validates: Requirements 4.1, 4.3
 */
class JavaPoetGeneratorPropertyTest {

    // -------------------------------------------------------------------------
    // Arbitraries
    // -------------------------------------------------------------------------

    /** Generates a single valid Java identifier segment (lowercase letters). */
    @Provide
    Arbitrary<String> segment() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(8);
    }

    /** Generates a simple class name starting with an uppercase letter. */
    @Provide
    Arbitrary<String> simpleName() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(8)
                .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1));
    }

    /**
     * Generates a binary class name with 1-4 package segments plus a simple class name,
     * using '/' as separator (e.g., "com/example/Foo").
     */
    @Provide
    Arbitrary<String> packagedBinaryName() {
        return Combinators.combine(
                segment().list().ofMinSize(1).ofMaxSize(4),
                simpleName()
        ).as((segments, cls) -> String.join("/", segments) + "/" + cls);
    }

    /** Generates a default-package binary class name (no '/' separator, e.g., "Foo"). */
    @Provide
    Arbitrary<String> defaultPackageBinaryName() {
        return simpleName();
    }

    // -------------------------------------------------------------------------
    // Property 3: JavaPoetGenerator package correctness
    // Feature: generated-class-naming, Property 3: JavaPoetGenerator package correctness
    // -------------------------------------------------------------------------

    /**
     * For any binary class name with a package, resolvePackageName() must return
     * the enclosing package with ".generated" appended.
     *
     * <p>Validates: Requirements 4.1, 4.3
     */
    @Property(tries = 100)
    void property3_packagedClassAppendsDotGenerated(
            @ForAll("packagedBinaryName") String binaryName
    ) {
        String result = JavaPoetGenerator.resolvePackageName(binaryName);

        assertTrue(result.endsWith(".generated"),
                "result must end with '.generated' but was: " + result);

        // Verify the prefix matches the original package (with '/' replaced by '.')
        String dotName = binaryName.replace('/', '.');
        String expectedPkg = dotName.substring(0, dotName.lastIndexOf('.'));
        assertEquals(expectedPkg + ".generated", result,
                "must be original package + '.generated'");
    }

    /**
     * For any default-package binary class name (no '/'), resolvePackageName()
     * must return exactly "" (empty string — stay in the default package).
     *
     * <p>Validates: Requirements 4.1, 4.3
     */
    @Property(tries = 100)
    void property3_defaultPackageProducesEmptyString(
            @ForAll("defaultPackageBinaryName") String binaryName
    ) {
        String result = JavaPoetGenerator.resolvePackageName(binaryName);

        assertEquals("", result,
                "default package must produce '' but was: " + result);
    }
}
