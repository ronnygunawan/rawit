package rawit.processors.inject;

import net.jqwik.api.*;
import org.objectweb.asm.*;
import rawit.processors.model.AnnotatedField;

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
 * Property-based tests for {@link GetterBytecodeInjector}.
 *
 * <p>Tests Properties 2, 3, and 4 from the getter-annotation design:
 * <ul>
 *   <li>Property 2: Static/instance modifier matching</li>
 *   <li>Property 3: Return type matches field type including generics</li>
 *   <li>Property 4: Generated getter is always public</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 2.3, 2.5, 3.1, 3.2, 7.1, 9.1, 9.2</b>
 */
class GetterBytecodeInjectorPropertyTest {

    // -------------------------------------------------------------------------
    // Arbitraries
    // -------------------------------------------------------------------------

    /** Field types: descriptor, Java source keyword, and optional generic signature. */
    private record FieldType(String descriptor, String javaType, String genericSignature) {}

    private static final List<FieldType> FIELD_TYPES = List.of(
            new FieldType("I", "int", null),
            new FieldType("J", "long", null),
            new FieldType("Z", "boolean", null),
            new FieldType("D", "double", null),
            new FieldType("F", "float", null),
            new FieldType("B", "byte", null),
            new FieldType("C", "char", null),
            new FieldType("S", "short", null),
            new FieldType("Ljava/lang/String;", "String", null),
            new FieldType("Ljava/lang/Object;", "Object", null),
            new FieldType("Ljava/util/List;", "java.util.List<String>",
                    "Ljava/util/List<Ljava/lang/String;>;"),
            new FieldType("Ljava/util/Map;", "java.util.Map<String, Integer>",
                    "Ljava/util/Map<Ljava/lang/String;Ljava/lang/Integer;>;")
    );

    private static final List<String> FIELD_NAMES = List.of(
            "name", "value", "count", "active", "data", "items", "label");

    @Provide
    Arbitrary<FieldType> anyFieldType() {
        return Arbitraries.of(FIELD_TYPES);
    }

    @Provide
    Arbitrary<String> anyFieldName() {
        return Arbitraries.of(FIELD_NAMES);
    }

    @Provide
    Arbitrary<Boolean> anyIsStatic() {
        return Arbitraries.of(true, false);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String uniqueClassName(final String base) {
        return base + "_" + Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
    }

    private static Path compileClass(final String className, final String source,
                                     final Path outputDir) throws Exception {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JavaCompiler not available");
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
                throw new RuntimeException("Compilation failed for " + className + ": "
                        + diagnostics.getDiagnostics());
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

    private static void deleteDir(final Path dir) {
        try {
            if (Files.exists(dir)) {
                Files.walk(dir).sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        } catch (final IOException ignored) {}
    }

    private static String capitalize(final String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Build source code for a class with a single field. */
    private static String buildSource(final String className, final String fieldName,
                                      final FieldType fieldType, final boolean isStatic) {
        final StringBuilder sb = new StringBuilder();
        sb.append("public class ").append(className).append(" {\n");
        sb.append("    ");
        if (isStatic) sb.append("static ");
        sb.append("private ").append(fieldType.javaType()).append(" ").append(fieldName).append(";\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** Build an AnnotatedField model for the given parameters. */
    private static AnnotatedField buildAnnotatedField(final String className, final String fieldName,
                                                       final FieldType fieldType, final boolean isStatic) {
        final String getterName = "get" + capitalize(fieldName);
        return new AnnotatedField(
                className,
                fieldName,
                fieldType.descriptor(),
                fieldType.genericSignature(),
                isStatic,
                getterName
        );
    }

    /** Holder for method info read back from bytecode. */
    private record MethodInfo(int access, String descriptor, String signature) {}

    /** Reads the zero-param method with the given name from a .class file, or null. */
    private static MethodInfo readGetterMethod(final Path classFile, final String methodName)
            throws IOException {
        final MethodInfo[] result = {null};
        final ClassReader reader = new ClassReader(Files.readAllBytes(classFile));
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                if (name.equals(methodName) && descriptor.startsWith("()")) {
                    result[0] = new MethodInfo(access, descriptor, signature);
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result[0];
    }

    // -------------------------------------------------------------------------
    // Property 2: Static/instance modifier matching
    // Feature: getter-annotation, Property 2: Static/instance modifier matching
    // Validates: Requirements 3.1, 3.2
    // -------------------------------------------------------------------------

    @Property(tries = 5)
    void property2_staticInstanceModifierMatching(
            @ForAll("anyFieldName") String fieldName,
            @ForAll("anyFieldType") FieldType fieldType,
            @ForAll("anyIsStatic") boolean isStatic
    ) throws Exception {
        final String className = uniqueClassName("GetProp2");
        final String source = buildSource(className, fieldName, fieldType, isStatic);

        final Path tempDir = Files.createTempDirectory("getprop2_");
        try {
            final Path classFile = compileClass(className, source, tempDir);

            final AnnotatedField field = buildAnnotatedField(className, fieldName, fieldType, isStatic);
            final List<String> errors = new ArrayList<>();
            new GetterBytecodeInjector().inject(classFile, List.of(field), mockEnv(errors));

            assertTrue(errors.isEmpty(), "no errors expected: " + errors);

            final MethodInfo getter = readGetterMethod(classFile, field.getterName());
            assertNotNull(getter, "getter method must exist after injection");

            final boolean getterIsStatic = (getter.access() & Opcodes.ACC_STATIC) != 0;
            assertEquals(isStatic, getterIsStatic,
                    "static field → static getter, instance field → instance getter"
                            + " (field isStatic=" + isStatic + ", getter isStatic=" + getterIsStatic + ")");
        } finally {
            deleteDir(tempDir);
        }
    }

    // -------------------------------------------------------------------------
    // Property 3: Return type matches field type including generics
    // Feature: getter-annotation, Property 3: Return type matches field type including generics
    // Validates: Requirements 2.3, 9.1, 9.2
    // -------------------------------------------------------------------------

    @Property(tries = 5)
    void property3_returnTypeMatchesFieldType(
            @ForAll("anyFieldName") String fieldName,
            @ForAll("anyFieldType") FieldType fieldType,
            @ForAll("anyIsStatic") boolean isStatic
    ) throws Exception {
        final String className = uniqueClassName("GetProp3");
        final String source = buildSource(className, fieldName, fieldType, isStatic);

        final Path tempDir = Files.createTempDirectory("getprop3_");
        try {
            final Path classFile = compileClass(className, source, tempDir);

            final AnnotatedField field = buildAnnotatedField(className, fieldName, fieldType, isStatic);
            final List<String> errors = new ArrayList<>();
            new GetterBytecodeInjector().inject(classFile, List.of(field), mockEnv(errors));

            assertTrue(errors.isEmpty(), "no errors expected: " + errors);

            final MethodInfo getter = readGetterMethod(classFile, field.getterName());
            assertNotNull(getter, "getter method must exist after injection");

            // Assert: getter descriptor matches "()" + field type descriptor
            final String expectedDescriptor = "()" + fieldType.descriptor();
            assertEquals(expectedDescriptor, getter.descriptor(),
                    "getter return descriptor must match field type descriptor");

            // Assert: getter generic signature matches "()" + field type signature (if present)
            if (fieldType.genericSignature() != null) {
                final String expectedSignature = "()" + fieldType.genericSignature();
                assertEquals(expectedSignature, getter.signature(),
                        "getter generic signature must match '()' + field type signature");
            } else {
                assertNull(getter.signature(),
                        "getter must have no generic signature when field has no generic signature");
            }
        } finally {
            deleteDir(tempDir);
        }
    }

    // -------------------------------------------------------------------------
    // Property 4: Generated getter is always public
    // Feature: getter-annotation, Property 4: Generated getter is always public
    // Validates: Requirements 2.5, 7.1
    // -------------------------------------------------------------------------

    @Property(tries = 5)
    void property4_generatedGetterIsAlwaysPublic(
            @ForAll("anyFieldName") String fieldName,
            @ForAll("anyFieldType") FieldType fieldType,
            @ForAll("anyIsStatic") boolean isStatic
    ) throws Exception {
        final String className = uniqueClassName("GetProp4");
        final String source = buildSource(className, fieldName, fieldType, isStatic);

        final Path tempDir = Files.createTempDirectory("getprop4_");
        try {
            final Path classFile = compileClass(className, source, tempDir);

            final AnnotatedField field = buildAnnotatedField(className, fieldName, fieldType, isStatic);
            final List<String> errors = new ArrayList<>();
            new GetterBytecodeInjector().inject(classFile, List.of(field), mockEnv(errors));

            assertTrue(errors.isEmpty(), "no errors expected: " + errors);

            final MethodInfo getter = readGetterMethod(classFile, field.getterName());
            assertNotNull(getter, "getter method must exist after injection");

            // Assert: getter has ACC_PUBLIC
            assertTrue((getter.access() & Opcodes.ACC_PUBLIC) != 0,
                    "getter must always be public");

            // Assert: only ACC_PUBLIC and optionally ACC_STATIC — no other flags
            final int expectedAccess = isStatic
                    ? Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC
                    : Opcodes.ACC_PUBLIC;
            assertEquals(expectedAccess, getter.access(),
                    "getter access must be ACC_PUBLIC" + (isStatic ? " | ACC_STATIC" : "")
                            + " but was " + getter.access());
        } finally {
            deleteDir(tempDir);
        }
    }
}
