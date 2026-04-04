package rawit;

import net.jqwik.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based test verifying that every .java source file has a package declaration
 * consistent with its directory path, that no file resides under a legacy rg/rawit path,
 * and that no file uses the default (unnamed) package.
 *
 * <p><b>Validates: Requirements 6.1, 6.2, 6.3</b>
 *
 * <p>Tag: {@code Feature: maven-central-deployment, Property 1: Source file package consistency}
 */
@Tag("Feature: maven-central-deployment, Property 1: Source file package consistency")
class SourceFilePackageConsistencyTest {

    private static final Path MAIN_ROOT = Paths.get("src/main/java");
    private static final Path TEST_ROOT = Paths.get("src/test/java");

    @Provide
    Arbitrary<Path> anyJavaFile() throws IOException {
        List<Path> files = collectJavaFiles();
        return Arbitraries.of(files);
    }

    private List<Path> collectJavaFiles() throws IOException {
        try (Stream<Path> mainFiles = Files.walk(MAIN_ROOT);
             Stream<Path> testFiles = Files.walk(TEST_ROOT)) {
            return Stream.concat(mainFiles, testFiles)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .toList();
        }
    }

    /**
     * Property 1: Source file package consistency
     *
     * <p>For every .java file:
     * <ol>
     *   <li>The file path must not contain {@code rg/rawit} or {@code rg\rawit}.</li>
     *   <li>The {@code package} declaration must match the directory path relative to the source root.</li>
     * </ol>
     *
     * <p><b>Validates: Requirements 6.1, 6.2, 6.3</b>
     */
    @Property(generation = GenerationMode.EXHAUSTIVE)
    void property1_sourceFilePackageConsistency(@ForAll("anyJavaFile") Path javaFile) throws IOException {
        String normalizedPath = javaFile.toString().replace('\\', '/');

        // Assertion 1: path must not contain rg/rawit
        assertFalse(
                normalizedPath.contains("rg/rawit"),
                "File path must not contain 'rg/rawit': " + javaFile
        );

        // Determine which source root this file belongs to
        Path sourceRoot = javaFile.startsWith(MAIN_ROOT) ? MAIN_ROOT : TEST_ROOT;

        // Assertion 2: package declaration must match directory path relative to source root
        Path relativePath = sourceRoot.relativize(javaFile);
        Path parentRelative = relativePath.getParent();

        List<String> lines = Files.readAllLines(javaFile);
        String packageDeclaration = lines.stream()
                .map(String::trim)
                .filter(line -> line.startsWith("package "))
                .findFirst()
                .orElse(null);

        // All source files under src/main/java or src/test/java must have a package declaration
        assertNotNull(
                packageDeclaration,
                "File has no package declaration (default package not allowed): " + javaFile
        );

        // Extract package name: "package rawit.processors.model;" -> "rawit.processors.model"
        String packageName = packageDeclaration
                .substring("package ".length())
                .replace(";", "")
                .trim();

        // Convert package name to expected relative directory path
        String expectedRelativeDir = packageName.replace('.', '/');

        String actualRelativeDir = parentRelative != null
                ? parentRelative.toString().replace('\\', '/')
                : "";

        assertEquals(
                expectedRelativeDir,
                actualRelativeDir,
                "Package declaration '" + packageName + "' does not match directory path '" +
                        actualRelativeDir + "' in file: " + javaFile
        );
    }
}
