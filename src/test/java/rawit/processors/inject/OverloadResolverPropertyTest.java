package rawit.processors.inject;

import net.jqwik.api.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for {@link OverloadResolver}.
 *
 * <p>Each property runs a minimum of 100 iterations via jqwik.
 */
class OverloadResolverPropertyTest {

    // -------------------------------------------------------------------------
    // Arbitraries
    // -------------------------------------------------------------------------

    /** Simple class names. */
    private static final List<String> SIMPLE_NAMES = List.of(
            "Foo", "Bar", "MyClass", "Handler", "Service", "Util", "Config", "Data");

    /** Package segments. */
    private static final List<String> PACKAGE_SEGMENTS = List.of(
            "com", "org", "net", "io", "example", "model", "util", "service");

    /**
     * Generates valid binary class names in slash-separated form.
     * Examples: "Foo", "com/Foo", "com/example/Foo", "org/model/service/Bar".
     */
    @Provide
    Arbitrary<String> validBinaryClassName() {
        final Arbitrary<String> simpleName = Arbitraries.of(SIMPLE_NAMES);
        final Arbitrary<List<String>> packagePath = Arbitraries.of(PACKAGE_SEGMENTS)
                .list().ofMinSize(0).ofMaxSize(3);
        return Combinators.combine(packagePath, simpleName).as((pkgParts, name) -> {
            if (pkgParts.isEmpty()) return name;
            return String.join("/", pkgParts) + "/" + name;
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a mock {@link ProcessingEnvironment} whose {@link Filer} returns a
     * {@code file:} URI for any {@code getResource(CLASS_OUTPUT, ...)} call.
     * The URI points to {@code {outputDir}/{packagePath}/{simpleName}}.
     */
    private static ProcessingEnvironment mockEnvWithFiler(final Path outputDir) {
        final Filer filer = new Filer() {
            @Override
            public JavaFileObject createSourceFile(CharSequence name, Element... originatingElements) {
                throw new UnsupportedOperationException();
            }

            @Override
            public JavaFileObject createClassFile(CharSequence name, Element... originatingElements) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FileObject createResource(JavaFileManager.Location location, CharSequence moduleAndPkg,
                                             CharSequence relativeName, Element... originatingElements) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FileObject getResource(JavaFileManager.Location location, CharSequence moduleAndPkg,
                                          CharSequence relativeName) throws IOException {
                // Build a file: URI from the output dir, package, and relative name
                final String pkg = moduleAndPkg.toString();
                final String rel = relativeName.toString();
                final Path resolved;
                if (pkg.isEmpty()) {
                    resolved = outputDir.resolve(rel);
                } else {
                    resolved = outputDir.resolve(pkg.replace('.', '/')).resolve(rel);
                }
                final URI uri = resolved.toUri();
                return new SimpleJavaFileObject(uri, JavaFileObject.Kind.CLASS) {
                    @Override
                    public URI toUri() {
                        return uri;
                    }
                };
            }
        };

        return new ProcessingEnvironment() {
            @Override public Map<String, String> getOptions() { return Map.of(); }
            @Override public Messager getMessager() { return null; }
            @Override public Filer getFiler() { return filer; }
            @Override public Elements getElementUtils() { return null; }
            @Override public Types getTypeUtils() { return null; }
            @Override public SourceVersion getSourceVersion() { return SourceVersion.latestSupported(); }
            @Override public Locale getLocale() { return Locale.getDefault(); }
        };
    }

    /**
     * Extracts the simple class name from a slash-separated binary class name.
     * E.g. "com/example/Foo" → "Foo", "Bar" → "Bar".
     */
    private static String simpleName(final String binaryClassName) {
        final int lastSlash = binaryClassName.lastIndexOf('/');
        return lastSlash < 0 ? binaryClassName : binaryClassName.substring(lastSlash + 1);
    }

    // -------------------------------------------------------------------------
    // Property 4: resolvePath returns path without existence check
    // Feature: single-pass-compilation, Property 4: resolvePath returns path without existence check
    // Validates: Requirements 5.1
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void property4_resolvePathReturnsPathWithoutExistenceCheck(
            @ForAll("validBinaryClassName") String binaryClassName
    ) throws Exception {
        // Use a temp directory as the output root — the file does NOT need to exist
        final Path outputDir = Path.of(System.getProperty("java.io.tmpdir"), "resolvePath_prop4");
        final ProcessingEnvironment env = mockEnvWithFiler(outputDir);

        final OverloadResolver resolver = new OverloadResolver();
        final Optional<Path> result = resolver.resolvePath(binaryClassName, env);

        // resolvePath must return a non-empty Optional for any valid binary class name
        // mapping to a file: URI, regardless of file existence
        assertTrue(result.isPresent(),
                "resolvePath() must return non-empty Optional for binary class name: " + binaryClassName);

        // The returned path must end with {simpleName}.class
        final String expectedFileName = simpleName(binaryClassName) + ".class";
        assertEquals(expectedFileName, result.get().getFileName().toString(),
                "returned path must end with " + expectedFileName + " for: " + binaryClassName);
    }

    // -------------------------------------------------------------------------
    // Property 5: resolve delegates to resolvePath with existence filter
    // Feature: single-pass-compilation, Property 5: resolve delegates to resolvePath with existence filter
    // Validates: Requirements 5.2
    // -------------------------------------------------------------------------

    @Property(tries = 100)
    void property5_resolveDelegatesToResolvePathWithExistenceFilter(
            @ForAll("validBinaryClassName") String binaryClassName,
            @ForAll boolean fileExists
    ) throws Exception {
        // Use a unique temp directory per invocation to avoid cross-test interference
        final Path outputDir = Files.createTempDirectory("resolvePath_prop5");
        final ProcessingEnvironment env = mockEnvWithFiler(outputDir);

        final OverloadResolver resolver = new OverloadResolver();

        // Determine the path that resolvePath would return
        final Optional<Path> resolvedPath = resolver.resolvePath(binaryClassName, env);
        assertTrue(resolvedPath.isPresent(), "resolvePath must return a path for valid binary class name");

        // Optionally create the file on disk so Files.exists returns true
        if (fileExists) {
            final Path classFile = resolvedPath.get();
            Files.createDirectories(classFile.getParent());
            Files.createFile(classFile);
        }

        // The property: resolve(name, env) == resolvePath(name, env).filter(Files::exists)
        final Optional<Path> resolveResult = resolver.resolve(binaryClassName, env);
        final Optional<Path> expected = resolver.resolvePath(binaryClassName, env).filter(Files::exists);

        assertEquals(expected, resolveResult,
                "resolve() must equal resolvePath().filter(Files::exists) for: " + binaryClassName
                        + " (fileExists=" + fileExists + ")");

        // Clean up temp directory
        if (fileExists) {
            Files.deleteIfExists(resolvedPath.get());
        }
        // Delete package directories (best-effort)
        try {
            Path dir = resolvedPath.get().getParent();
            while (dir != null && dir.startsWith(outputDir) && !dir.equals(outputDir)) {
                Files.deleteIfExists(dir);
                dir = dir.getParent();
            }
        } catch (final IOException ignored) { }
        Files.deleteIfExists(outputDir);
    }
}
