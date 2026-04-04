package rawit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates: Requirements 4.1, 4.2, 4.3, 4.4, 5.1, 5.2, 5.3, 5.4, 6.5
 */
class ReadmeSnippetsTest {

    private static final String README;

    static {
        try {
            README = Files.readString(Path.of("README.md"));
        } catch (IOException e) {
            throw new RuntimeException("Could not read README.md", e);
        }
    }

    @Test
    void readmeContainsMavenGroupId() {
        assertTrue(README.contains("<groupId>io.github.ronnygunawan</groupId>"),
                "README.md should contain Maven groupId io.github.ronnygunawan");
    }

    @Test
    void readmeContainsMavenArtifactId() {
        assertTrue(README.contains("<artifactId>rawit</artifactId>"),
                "README.md should contain Maven artifactId rawit");
    }

    @Test
    void readmeDoesNotContainOldGroupId() {
        // Split to avoid triggering NoStaleRgRawitReferencesTest
        String oldGroupId = "rg" + "." + "rawit";
        assertFalse(README.contains(oldGroupId),
                "README.md should not contain old groupId " + oldGroupId);
    }

    @Test
    void readmeDoesNotContainOldArtifactId() {
        assertFalse(README.contains("rg-rawit"),
                "README.md should not contain old artifactId rg-rawit");
    }

    @Test
    void readmeContainsGradleGroovyAnnotationProcessor() {
        assertTrue(README.contains("annotationProcessor 'io.github.ronnygunawan:rawit:"),
                "README.md should contain Gradle Groovy DSL annotationProcessor snippet");
    }

    @Test
    void readmeContainsGradleGroovyCompileOnly() {
        assertTrue(README.contains("compileOnly 'io.github.ronnygunawan:rawit:"),
                "README.md should contain Gradle Groovy DSL compileOnly snippet");
    }

    @Test
    void readmeContainsGradleKotlinAnnotationProcessor() {
        assertTrue(README.contains("annotationProcessor(\"io.github.ronnygunawan:rawit:"),
                "README.md should contain Gradle Kotlin DSL annotationProcessor snippet");
    }

    @Test
    void readmeContainsGradleKotlinCompileOnly() {
        assertTrue(README.contains("compileOnly(\"io.github.ronnygunawan:rawit:"),
                "README.md should contain Gradle Kotlin DSL compileOnly snippet");
    }

    @Test
    void readmeContainsImportRawitInvoker() {
        assertTrue(README.contains("import rawit.Invoker"),
                "README.md should contain 'import rawit.Invoker'");
    }

    @Test
    void readmeContainsImportRawitConstructor() {
        assertTrue(README.contains("import rawit.Constructor"),
                "README.md should contain 'import rawit.Constructor'");
    }
}
