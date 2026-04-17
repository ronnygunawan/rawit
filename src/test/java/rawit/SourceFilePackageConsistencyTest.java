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
 * consistent with its directory path, and that no file uses the default (unnamed) package.
 */
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
     * Property: Source file package consistency
     *
     * <p>For every .java file, the {@code package} declaration must match the directory
     * path relative to the source root, and the file must have an explicit package declaration
     * (default/unnamed package is not allowed).
     */
    @Property(generation = GenerationMode.EXHAUSTIVE)
    void sourceFilePackageConsistency(@ForAll("anyJavaFile") Path javaFile) throws IOException {
        // Determine which source root this file belongs to
        Path sourceRoot = javaFile.startsWith(MAIN_ROOT) ? MAIN_ROOT : TEST_ROOT;

        // package declaration must match directory path relative to source root
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
