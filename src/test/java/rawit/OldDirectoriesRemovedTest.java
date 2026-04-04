package rawit;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Validates: Requirements 6.6
 */
class OldDirectoriesRemovedTest {

    @Test
    void oldMainSourceDirectoryDoesNotExist() {
        assertFalse(Files.exists(Path.of("src/main/java/rg")),
                "src/main/java/rg/ should not exist after package rename");
    }

    @Test
    void oldTestSourceDirectoryDoesNotExist() {
        assertFalse(Files.exists(Path.of("src/test/java/rg")),
                "src/test/java/rg/ should not exist after package rename");
    }
}
