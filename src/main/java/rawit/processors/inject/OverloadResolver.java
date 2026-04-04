package rawit.processors.inject;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.JavaFileManager;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Resolves the {@code .class} file path for a given binary class name from the build output
 * directory, using the {@link ProcessingEnvironment}'s {@link javax.tools.JavaFileManager}.
 *
 * <p>The binary class name uses slash-separated form, e.g. {@code "com/example/Foo"}.
 */
public class OverloadResolver {

    /** Creates a new {@code OverloadResolver}. */
    public OverloadResolver() {}

    /**
     * Resolves the {@code .class} file path for the given binary class name.
     *
     * <p>Uses {@link javax.annotation.processing.Filer#getResource(javax.tools.JavaFileManager.Location, CharSequence, CharSequence)} with {@link StandardLocation#CLASS_OUTPUT}
     * to locate the {@code .class} file without any side effects (no probe files created).
     *
     * @param binaryClassName slash-separated binary class name, e.g. {@code "com/example/Foo"}
     * @param env             the processing environment
     * @return an {@link Optional} containing the path if the file exists, or empty otherwise
     */
    public Optional<Path> resolve(final String binaryClassName, final ProcessingEnvironment env) {
        // Use getResource to locate the .class file — no side effects, no probe files.
        try {
            final String packageName = toPackageName(binaryClassName);
            final String simpleName = toSimpleName(binaryClassName) + ".class";
            final javax.tools.FileObject resource = env.getFiler().getResource(
                    StandardLocation.CLASS_OUTPUT, packageName, simpleName);
            final java.net.URI uri = resource.toUri();
            // Only attempt Path conversion for file: URIs — other schemes (e.g. jar:, mem:)
            // are not addressable as a default-filesystem Path.
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                return Optional.empty();
            }
            final Path classFile = Paths.get(uri);
            if (Files.exists(classFile)) {
                return Optional.of(classFile);
            }
        } catch (final IOException | IllegalArgumentException e) {
            // Fall through — file not found or URI not convertible
        }

        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String toPackageName(final String binaryClassName) {
        final int lastSlash = binaryClassName.lastIndexOf('/');
        if (lastSlash < 0) return "";
        return binaryClassName.substring(0, lastSlash).replace('/', '.');
    }

    private static String toSimpleName(final String binaryClassName) {
        final int lastSlash = binaryClassName.lastIndexOf('/');
        return lastSlash < 0 ? binaryClassName : binaryClassName.substring(lastSlash + 1);
    }
}
