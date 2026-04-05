package rawit.processors.inject;

import net.jqwik.api.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.util.CheckClassAdapter;
import rawit.processors.model.*;
import rawit.processors.model.MergeNode.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for Java 17 bytecode version compatibility.
 *
 * <p>Property 1: Processor class files have bytecode version &lt;= 61
 * <p><b>Validates: Requirements 1.1</b>
 */
class BytecodeVersionPropertyTest {

    private static final int JAVA_17_MAJOR_VERSION = 61;
    private static final Path CLASSES_ROOT = Paths.get("target/classes/rawit");

    // -------------------------------------------------------------------------
    // Property 1: Processor class files have bytecode version <= 61
    // Feature: java17-compatibility
    // Property 1: Processor class files have bytecode version <= 61
    // Validates: Requirements 1.1
    // -------------------------------------------------------------------------

    @Test
    void property1_processorClassFilesHaveBytecodeVersionAtMost61() throws IOException {
        assertTrue(Files.exists(CLASSES_ROOT),
                "Build output directory must exist: " + CLASSES_ROOT.toAbsolutePath()
                + " — run 'mvn compile' first");

        final List<Path> classFiles;
        try (Stream<Path> walk = Files.walk(CLASSES_ROOT)) {
            classFiles = walk
                    .filter(p -> p.toString().endsWith(".class"))
                    .toList();
        }

        assertTrue(!classFiles.isEmpty(),
                "No .class files found under " + CLASSES_ROOT.toAbsolutePath());

        final List<String> violations = new ArrayList<>();
        for (final Path classFile : classFiles) {
            final byte[] bytes = Files.readAllBytes(classFile);
            // Class file header: magic (4 bytes), minor_version (2 bytes), major_version (2 bytes)
            // major_version is at bytes 6–7 (big-endian)
            final int majorVersion = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
            if (majorVersion > JAVA_17_MAJOR_VERSION) {
                violations.add(classFile.getFileName() + ": major_version=" + majorVersion
                        + " (expected <= " + JAVA_17_MAJOR_VERSION + ")");
            }
        }

        if (!violations.isEmpty()) {
            fail("The following class files exceed Java 17 bytecode version (61):\n"
                    + String.join("\n", violations));
        }
    }

    // -------------------------------------------------------------------------
    // Property 2: BytecodeInjector preserves bytecode version through injection
    // Feature: java17-compatibility
    // Property 2: BytecodeInjector preserves bytecode version through injection
    // Validates: Requirements 3.3
    // -------------------------------------------------------------------------

    /** Java major versions 17–21 (61–65). */
    @Provide
    Arbitrary<Integer> anyJavaMajorVersion() {
        return Arbitraries.of(61, 62, 63, 64, 65);
    }

    @Property(tries = 100)
    void property2_bytecodeInjectorPreservesBytecodeVersion(
            @ForAll("anyJavaMajorVersion") int majorVersion
    ) throws Exception {
        // Build a minimal synthetic .class file at the given major_version using ASM
        final String className = "SyntheticClass_v" + majorVersion + "_" + Thread.currentThread().getId();
        final byte[] syntheticBytes = buildSyntheticClass(className, majorVersion);

        final Path tempDir = Files.createTempDirectory("prop2_version_");
        try {
            final Path classFile = tempDir.resolve(className + ".class");
            Files.write(classFile, syntheticBytes);

            // Build a minimal MergeTree so the injector has something to inject
            final List<Parameter> params = List.of(new Parameter("x", "I"));
            final AnnotatedMethod method = new AnnotatedMethod(
                    className, "compute", false, false,
                    params, "I", List.of(), Opcodes.ACC_PUBLIC);
            final MergeTree tree = buildLinearTree(method);

            final List<String> errors = new ArrayList<>();
            new BytecodeInjector().inject(classFile, List.of(tree), buildMockEnv(errors));

            assertTrue(errors.isEmpty(), "no errors expected during injection: " + errors);

            // Read back the major_version from the modified class file
            final byte[] resultBytes = Files.readAllBytes(classFile);
            final int resultMajorVersion = ((resultBytes[6] & 0xFF) << 8) | (resultBytes[7] & 0xFF);

            assertEquals(majorVersion, resultMajorVersion,
                    "BytecodeInjector must preserve major_version=" + majorVersion
                            + " but got " + resultMajorVersion);
        } finally {
            deleteDir(tempDir);
        }
    }

    // -------------------------------------------------------------------------
    // Property 3: BytecodeInjector produces verifiable bytecode for Java 17 class files
    // Feature: java17-compatibility
    // Property 3: BytecodeInjector produces verifiable bytecode for Java 17 class files
    // Validates: Requirements 3.1, 3.2
    // -------------------------------------------------------------------------

    private static final List<String> METHOD_NAMES =
            List.of("compute", "process", "handle", "execute", "run", "apply", "transform");
    private static final List<String> PARAM_TYPES = List.of("I", "J", "Z", "D");
    private static final List<String> PARAM_NAMES_LIST = List.of("a", "b", "c", "x", "y", "z");

    @Provide
    Arbitrary<String> anyMethodName() {
        return Arbitraries.of(METHOD_NAMES);
    }

    @Provide
    Arbitrary<List<Parameter>> anyParamList() {
        return Combinators.combine(
                        Arbitraries.of(PARAM_NAMES_LIST),
                        Arbitraries.of(PARAM_TYPES))
                .as(Parameter::new)
                .list().ofMinSize(1).ofMaxSize(3)
                .filter(ps -> ps.stream().map(Parameter::name).distinct().count() == ps.size());
    }

    @Property(tries = 100)
    void property3_bytecodeInjectorProducesVerifiableBytecodeForJava17ClassFiles(
            @ForAll("anyMethodName") String methodName,
            @ForAll("anyParamList") List<Parameter> params
    ) throws Exception {
        final String className = "SyntheticJava17_" + methodName + "_"
                + Thread.currentThread().getId() + "_" + System.nanoTime();
        final byte[] syntheticBytes = buildSyntheticJava17Class(className, methodName, params);

        final Path tempDir = Files.createTempDirectory("prop3_verify_");
        try {
            final Path classFile = tempDir.resolve(className + ".class");
            Files.write(classFile, syntheticBytes);

            final AnnotatedMethod method = new AnnotatedMethod(
                    className, methodName, false, false,
                    params, "I", List.of(), Opcodes.ACC_PUBLIC);
            final MergeTree tree = buildLinearTree(method);

            final List<String> errors = new ArrayList<>();
            new BytecodeInjector().inject(classFile, List.of(tree), buildMockEnv(errors));

            assertTrue(errors.isEmpty(), "no errors expected during injection: " + errors);

            // Verify the resulting bytecode using ASM's CheckClassAdapter
            final byte[] resultBytes = Files.readAllBytes(classFile);
            final StringWriter sw = new StringWriter();
            final PrintWriter pw = new PrintWriter(sw);
            CheckClassAdapter.verify(new ClassReader(resultBytes), false, pw);
            pw.flush();
            final String verifyOutput = sw.toString();

            assertTrue(verifyOutput.isEmpty(),
                    "CheckClassAdapter.verify() must produce no output for Java 17 class file "
                            + "after injection, but got:\n" + verifyOutput);
        } finally {
            deleteDir(tempDir);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers for Property 2 and Property 3
    // -------------------------------------------------------------------------

    /**
     * Builds a synthetic Java 17 {@code .class} file (major_version = 61) with the given
     * method name and parameter list using ASM.
     */
    private static byte[] buildSyntheticJava17Class(final String className,
                                                     final String methodName,
                                                     final List<Parameter> params) {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(JAVA_17_MAJOR_VERSION, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);

        // Default constructor
        final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // The target method with the given params, returning int
        final String descriptor = buildDescriptor(params, "I");
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, descriptor, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 1 + params.size());
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Builds a JVM method descriptor from a parameter list and a return type descriptor. */
    private static String buildDescriptor(final List<Parameter> params, final String returnType) {
        final StringBuilder sb = new StringBuilder("(");
        for (final Parameter p : params) sb.append(p.typeDescriptor());
        sb.append(')').append(returnType);
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Helpers for Property 2
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal valid {@code .class} file at the given {@code major_version} using ASM.
     * The class has one instance method {@code compute(int x) : int} so the injector has a
     * real method to work with.
     */
    private static byte[] buildSyntheticClass(final String className, final int majorVersion) {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(majorVersion, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);

        // Default constructor
        final MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // compute(int x) : int
        final MethodVisitor compute = cw.visitMethod(Opcodes.ACC_PUBLIC, "compute", "(I)I", null, null);
        compute.visitCode();
        compute.visitVarInsn(Opcodes.ILOAD, 1);
        compute.visitInsn(Opcodes.IRETURN);
        compute.visitMaxs(1, 2);
        compute.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MergeTree buildLinearTree(final AnnotatedMethod m) {
        final OverloadGroup g = new OverloadGroup(m.enclosingClassName(), m.methodName(), List.of(m));
        final MergeNode root = buildChain(m.parameters(), 0, m);
        return new MergeTree(g, root);
    }

    private static MergeNode buildChain(final List<Parameter> params, final int pos,
                                         final AnnotatedMethod m) {
        if (pos == params.size()) return new TerminalNode(List.of(m), null);
        return new SharedNode(params.get(pos).name(), params.get(pos).typeDescriptor(),
                buildChain(params, pos + 1, m));
    }

    private static ProcessingEnvironment buildMockEnv(final List<String> errors) {
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
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (final IOException e) {
                            throw new UncheckedIOException("Failed to delete path: " + p, e);
                        }
                    });
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to walk directory: " + dir, e);
        }
    }
}
