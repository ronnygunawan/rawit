package rawit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServiceRegistrationTest {

    @Test
    void processorServiceFileRegistersRawitAnnotationProcessor() throws IOException {
        String content = Files.readString(
                Path.of("src/main/resources/META-INF/services/javax.annotation.processing.Processor")
        ).trim();
        assertEquals("rawit.processors.RawitAnnotationProcessor", content);
    }
}
