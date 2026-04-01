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

    /**
     * Resolves the {@code .class} file path for the given binary class name.
     *
     * <p>Uses the {@link ProcessingEnvironment} to locate the {@code CLASS_OUTPUT} directory,
     * then constructs the expected path from the binary class name.
     *
     * @param binaryClassName slash-separated binary class name, e.g. {@code "com/example/Foo"}
     * @param env             the processing environment
     * @return an {@link Optional} containing the path if the file exists, or empty otherwise
     */
    public Optional<Path> resolve(final String binaryClassName, final ProcessingEnvironment env) {
        // Strategy 1: use the Filer to create a dummy resource in CLASS_OUTPUT and derive the root
        try {
            final javax.tools.FileObject dummy = env.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", "__rawit_probe__");
            final Path probeUri = Paths.get(dummy.toUri());
            // The probe file is at <classOutputRoot>/__rawit_probe__
            // Delete it immediately — we only needed the path
            try {
                Files.deleteIfExists(probeUri);
            } catch (final IOException ignored) {
                // best-effort cleanup
            }
            final Path classOutputRoot = probeUri.getParent();
            if (classOutputRoot != null) {
                final Path classFile = classOutputRoot.resolve(binaryClassName + ".class");
                if (Files.exists(classFile)) {
                    return Optional.of(classFile);
                }
            }
        } catch (final IOException e) {
            // Fall through to strategy 2
        }

        // Strategy 2: try to get the resource directly via getResource
        try {
            final String packageName = toPackageName(binaryClassName);
            final String simpleName = toSimpleName(binaryClassName) + ".class";
            final javax.tools.FileObject resource = env.getFiler().getResource(
                    StandardLocation.CLASS_OUTPUT, packageName, simpleName);
            final Path classFile = Paths.get(resource.toUri());
            if (Files.exists(classFile)) {
                return Optional.of(classFile);
            }
        } catch (final IOException e) {
            // Fall through — file not found
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
