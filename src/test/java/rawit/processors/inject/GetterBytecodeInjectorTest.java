package rawit.processors.inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rawit.processors.model.AnnotatedField;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.*;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GetterBytecodeInjector}.
 *
 * <p>Injects getters into real {@code .class} files, loads the modified class,
 * invokes the getter via reflection, and verifies the returned value.
 *
 * <p><b>Validates: Requirements 2.1, 2.3, 2.4, 3.1, 3.2, 9.1</b>
 */
class GetterBytecodeInjectorTest {

    // -------------------------------------------------------------------------
    // Infrastructure helpers (same pattern as GetterBytecodeInjectorPropertyTest)
    // -------------------------------------------------------------------------

    private static Path compileClass(final String className, final String source,
                                     final Path outputDir) throws Exception {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JavaCompiler not available");
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (final StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
            final JavaFileObject sourceFile = new SimpleJavaFileObject(
                    URI.create("string:///" + className.replace('.', '/') + ".java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
            };
            final JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fm, diagnostics, List.of("-proc:none"), null, List.of(sourceFile));
            if (!task.call()) {
                final StringBuilder sb = new StringBuilder("Compilation failed:\n");
                for (final Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    sb.append(d).append('\n');
                }
                fail(sb.toString());
            }
        }
        return outputDir.resolve(className.replace('.', '/') + ".class");
    }

    private static ProcessingEnvironment mockEnv(final List<String> errors) {
        final Messager messager = new Messager() {
            @Override public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
                if (kind == Diagnostic.Kind.ERROR) errors.add(msg.toString());
            }
            @Override public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) { printMessage(kind, msg); }
            @Override public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) { printMessage(kind, msg); }
            @Override public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a, AnnotationValue v) { printMessage(kind, msg); }
        };
        return new ProcessingEnvironment() {
            @Override public Map<String, String> getOptions() { return Map.of(); }
            @Override public Messager getMessager() { return messager; }
            @Override public Filer getFiler() { return null; }
            @Override public Elements getElementUtils() { return null; }
            @Override public Types getTypeUtils() { return null; }
            @Override public SourceVersion getSourceVersion() { return SourceVersion.latestSupported(); }
            @Override public Locale getLocale() { return Locale.getDefault(); }
        };
    }

    /**
     * Loads a class from the given directory using a fresh URLClassLoader.
     * This ensures we pick up the modified bytecode.
     */
    private static Class<?> loadClass(final String className, final Path classDir) throws Exception {
        final URL url = classDir.toUri().toURL();
        try (final URLClassLoader loader = new URLClassLoader(new URL[]{url}, null)) {
            return loader.loadClass(className);
        }
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void inject_instanceStringField_getterReturnsFieldValue(@TempDir final Path tempDir) throws Exception {
        final String source = """
                public class StrHolder {
                    private String name;
                }
                """;
        final Path classFile = compileClass("StrHolder", source, tempDir);

        final AnnotatedField field = new AnnotatedField(
                "StrHolder", "name", "Ljava/lang/String;", null, false, "getName");

        final List<String> errors = new ArrayList<>();
        new GetterBytecodeInjector().inject(classFile, List.of(field), mockEnv(errors));
        assertTrue(errors.isEmpty(), "no errors expected: " + errors);

        final Class<?> clazz = loadClass("StrHolder", tempDir);
        final Object instance = clazz.getDeclaredConstructor().newInstance();

        // Set field value via reflection
        final Field f = clazz.getDeclaredField("name");
        f.setAccessible(true);
        f.set(instance, "hello");

        // Invoke getter and verify
        final Method getter = clazz.getMethod("getName");
        assertEquals("hello", getter.invoke(instance));
    }

    @Test
    void inject_staticIntField_getterReturnsFieldValue(@TempDir final Path tempDir) throws Exception {
        final String source = """
                public class CountHolder {
                    private static int count;
                }
                """;
        final Path classFile = compileClass("CountHolder", source, tempDir);

        final AnnotatedField field = new AnnotatedField(
                "CountHolder", "count", "I", null, true, "getCount");

        final List<String> errors = new ArrayList<>();
        new GetterBytecodeInjector().inject(classFile, List.of(field), mockEnv(errors));
        assertTrue(errors.isEmpty(), "no errors expected: " + errors);

        final Class<?> clazz = loadClass("CountHolder", tempDir);

        // Set static field via reflection
        final Field f = clazz.getDeclaredField("count");
        f.setAccessible(true);
        f.set(null, 42);

        // Invoke static getter and verify
        final Method getter = clazz.getMethod("getCount");
        assertEquals(42, getter.invoke(null));
    }

    @Test
    void inject_primitiveBooleanField_getterReturnsFieldValue(@TempDir final Path tempDir) throws Exception {
        final String source = """
                public class FlagHolder {
                    private boolean active;
                }
                """;
        final Path classFile = compileClass("FlagHolder", source, tempDir);

        final AnnotatedField field = new AnnotatedField(
                "FlagHolder", "active", "Z", null, false, "isActive");

        final List<String> errors = new ArrayList<>();
        new GetterBytecodeInjector().inject(classFile, List.of(field), mockEnv(errors));
        assertTrue(errors.isEmpty(), "no errors expected: " + errors);

        final Class<?> clazz = loadClass("FlagHolder", tempDir);
        final Object instance = clazz.getDeclaredConstructor().newInstance();

        // Set field value via reflection
        final Field f = clazz.getDeclaredField("active");
        f.setAccessible(true);
        f.set(instance, true);

        // Invoke getter and verify
        final Method getter = clazz.getMethod("isActive");
        assertEquals(true, getter.invoke(instance));
    }

    @Test
    void inject_genericListField_getterReturnsFieldValue(@TempDir final Path tempDir) throws Exception {
        final String source = """
                import java.util.List;
                public class ListHolder {
                    private List<String> items;
                }
                """;
        final Path classFile = compileClass("ListHolder", source, tempDir);

        final AnnotatedField field = new AnnotatedField(
                "ListHolder", "items", "Ljava/util/List;",
                "Ljava/util/List<Ljava/lang/String;>;", false, "getItems");

        final List<String> errors = new ArrayList<>();
        new GetterBytecodeInjector().inject(classFile, List.of(field), mockEnv(errors));
        assertTrue(errors.isEmpty(), "no errors expected: " + errors);

        final Class<?> clazz = loadClass("ListHolder", tempDir);
        final Object instance = clazz.getDeclaredConstructor().newInstance();

        // Set field value via reflection
        final Field f = clazz.getDeclaredField("items");
        f.setAccessible(true);
        final List<String> expected = List.of("a", "b", "c");
        f.set(instance, expected);

        // Invoke getter and verify
        final Method getter = clazz.getMethod("getItems");
        assertEquals(expected, getter.invoke(instance));

        // Verify generic return type is preserved in the method signature
        final java.lang.reflect.Type returnType = getter.getGenericReturnType();
        assertTrue(returnType instanceof java.lang.reflect.ParameterizedType,
                "getter return type must be parameterized");
        final java.lang.reflect.ParameterizedType pt = (java.lang.reflect.ParameterizedType) returnType;
        assertEquals(List.class, pt.getRawType());
        assertEquals(String.class, pt.getActualTypeArguments()[0]);
    }
}
