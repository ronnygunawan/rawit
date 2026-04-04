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
 * Property-based test verifying that no .java source file contains a stale legacy package
 * reference (package declaration, import statement, or string literal).
 *
 * <p><b>Validates: Requirements 6.3</b>
 *
 * <p>Tag: {@code Feature: maven-central-deployment, Property 2: No stale rg<dot>rawit references}
 */
@Tag("Feature: maven-central-deployment, Property 2: No stale rg<dot>rawit references")
class NoStaleRgRawitReferencesTest {

    private static final Path SRC_ROOT = Paths.get("src");

    // Split to avoid this file itself triggering the property
    private static final String STALE_PACKAGE = "rg" + "." + "rawit";

    @Provide
    Arbitrary<Path> anyJavaFile() throws IOException {
        Path thisFile = Paths.get(
                "src/test/java/rawit/NoStaleRgRawitReferencesTest.java");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(SRC_ROOT)) {
            files = walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.toAbsolutePath().equals(thisFile.toAbsolutePath()))
                    .toList();
        }
        return Arbitraries.of(files);
    }

    /**
     * Property 2: No stale legacy package references in source files
     *
     * <p>For every .java file under {@code src/} (excluding this test file itself),
     * the file content must not contain the legacy package string.
     *
     * <p><b>Validates: Requirements 6.3</b>
     */
    @Property(generation = GenerationMode.EXHAUSTIVE)
    void property2_noStaleRgRawitReferences(@ForAll("anyJavaFile") Path javaFile) throws IOException {
        String content = Files.readString(javaFile);
        assertFalse(
                content.contains(STALE_PACKAGE),
                "File must not contain '" + STALE_PACKAGE + "': " + javaFile
        );
    }
}
