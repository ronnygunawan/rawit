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
 * Property-based tests for the sample-tagged-value-demo feature.
 *
 * <p>Verifies source file identity across Maven and Gradle samples,
 * and correct rawit version references in build files.
 */
class SampleTaggedValueDemoPropertyTest {

    private static final Path MAVEN_SRC = Paths.get("samples/maven-sample/src");
    private static final Path GRADLE_SRC = Paths.get("samples/gradle-sample/src");

    // -------------------------------------------------------------------------
    // Arbitraries
    // -------------------------------------------------------------------------

    @Provide
    Arbitrary<Path> anyMavenJavaFile() throws IOException {
        return Arbitraries.of(collectJavaFiles(MAVEN_SRC));
    }

    @Provide
    Arbitrary<Path> anyGradleJavaFile() throws IOException {
        return Arbitraries.of(collectJavaFiles(GRADLE_SRC));
    }

    @Provide
    Arbitrary<Path> anyBuildFile() {
        return Arbitraries.of(List.of(
                Paths.get("samples/maven-sample/pom.xml"),
                Paths.get("samples/gradle-sample/build.gradle")
        ));
    }

    private List<Path> collectJavaFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .toList();
        }
    }

    // -------------------------------------------------------------------------
    // Property 1: Source file identity and completeness across samples
    // Feature: sample-tagged-value-demo, Property 1: Source file identity and completeness across samples
    // -------------------------------------------------------------------------

    /**
     * For every Java source file in the Maven sample, an identical counterpart
     * must exist in the Gradle sample at the same relative path, with byte-for-byte
     * identical content.
     *
     * <p><b>Validates: Requirements 2.4, 3.3, 4.3, 5.2, 6.3, 7.3, 8.3, 9.2, 10.2, 11.3, 12.1, 12.2</b>
     */
    @Property(generation = GenerationMode.EXHAUSTIVE)
    void property1_everyMavenFileHasIdenticalGradleCounterpart(
            @ForAll("anyMavenJavaFile") Path mavenFile
    ) throws IOException {
        Path relativePath = MAVEN_SRC.relativize(mavenFile);
        Path gradleFile = GRADLE_SRC.resolve(relativePath);

        assertTrue(Files.exists(gradleFile),
                "Gradle sample missing file: " + relativePath);

        byte[] mavenBytes = Files.readAllBytes(mavenFile);
        byte[] gradleBytes = Files.readAllBytes(gradleFile);

        assertArrayEquals(mavenBytes, gradleBytes,
                "File content differs: " + relativePath);
    }

    /**
     * For every Java source file in the Gradle sample, a counterpart must exist
     * in the Maven sample — ensuring the file sets are equal (no extra files in
     * Gradle that are missing from Maven).
     *
     * <p><b>Validates: Requirements 2.4, 3.3, 4.3, 5.2, 6.3, 7.3, 8.3, 9.2, 10.2, 11.3, 12.1, 12.2</b>
     */
    @Property(generation = GenerationMode.EXHAUSTIVE)
    void property1_everyGradleFileHasMavenCounterpart(
            @ForAll("anyGradleJavaFile") Path gradleFile
    ) throws IOException {
        Path relativePath = GRADLE_SRC.relativize(gradleFile);
        Path mavenFile = MAVEN_SRC.resolve(relativePath);

        assertTrue(Files.exists(mavenFile),
                "Maven sample missing file: " + relativePath);
    }

    // -------------------------------------------------------------------------
    // Property 2: Build files reference correct rawit version
    // Feature: sample-tagged-value-demo, Property 2: Build files reference correct rawit version
    // -------------------------------------------------------------------------

    /**
     * For each build configuration file (pom.xml, build.gradle), the file must
     * contain the version string {@code 1.1.0-rc-1} and must not reference
     * {@code 1.0.0} as a rawit dependency version.
     *
     * <p><b>Validates: Requirements 1.1, 1.2</b>
     */
    @Property(generation = GenerationMode.EXHAUSTIVE)
    void property2_buildFileReferencesCorrectRawitVersion(
            @ForAll("anyBuildFile") Path buildFile
    ) throws IOException {
        String content = Files.readString(buildFile);

        assertTrue(content.contains("1.1.0-rc-1"),
                buildFile.getFileName() + " must contain version 1.1.0-rc-1");

        // Check that 1.0.0 does not appear as a rawit dependency version.
        // For pom.xml: look for rawit artifactId near 1.0.0
        // For build.gradle: look for rawit:1.0.0
        String fileName = buildFile.getFileName().toString();
        if ("pom.xml".equals(fileName)) {
            // In pom.xml, rawit dependency version 1.0.0 would appear as
            // <version>1.0.0</version> near the rawit artifactId
            assertFalse(content.contains("rawit</artifactId>\n            <version>1.0.0</version>"),
                    "pom.xml must not reference rawit version 1.0.0");
        } else if ("build.gradle".equals(fileName)) {
            assertFalse(content.contains("rawit:1.0.0"),
                    "build.gradle must not reference rawit:1.0.0");
        }
    }
}
