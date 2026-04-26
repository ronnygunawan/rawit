package rawit.processors.inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import rawit.processors.inject.BytecodeInjector;
import rawit.processors.model.*;
import rawit.processors.model.MergeNode.*;

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
 * Unit tests for {@link BytecodeInjector}.
 *
 * <p>Compiles a minimal Java class to a temp directory, runs the injector, then uses ASM to
 * inspect the resulting bytecode and verify the injected parameterless overload.
 */
class BytecodeInjectorTest {

    // -------------------------------------------------------------------------
    // Infrastructure helpers
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

    private static MergeTree linearTree(final AnnotatedMethod m) {
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

    private static ProcessingEnvironment mockEnv(final List<String> notes,
                                                  final List<String> errors) {
        final Messager messager = new Messager() {
            @Override public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
                if (kind == Diagnostic.Kind.NOTE) notes.add(msg.toString());
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

    private static Map<String, List<String>> readMethods(final Path classFile) throws IOException {
        final Map<String, List<String>> result = new LinkedHashMap<>();
        final ClassReader reader = new ClassReader(Files.readAllBytes(classFile));
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                result.computeIfAbsent(name, k -> new ArrayList<>()).add(descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result;
    }

    private static boolean hasZeroParamMethod(final Path classFile, final String methodName)
            throws IOException {
        return readMethods(classFile).getOrDefault(methodName, List.of()).stream()
                .anyMatch(d -> d.startsWith("()"));
    }

    private static String zeroParamMethodDescriptor(final Path classFile, final String methodName)
            throws IOException {
        return readMethods(classFile).getOrDefault(methodName, List.of()).stream()
                .filter(d -> d.startsWith("()"))
                .findFirst().orElse(null);
    }

    private static int zeroParamMethodAccess(final Path classFile, final String methodName)
            throws IOException {
        final int[] result = {-1};
        final ClassReader reader = new ClassReader(Files.readAllBytes(classFile));
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                if (name.equals(methodName) && descriptor.startsWith("()")) {
                    result[0] = access;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result[0];
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void inject_instanceMethod_addsParameterlessOverload(@TempDir final Path tempDir) throws Exception {
        final String source = "public class FooInstance { public int bar(int x, int y) { return x + y; } }";
        final Path classFile = compileClass("FooInstance", source, tempDir);

        final AnnotatedMethod method = new AnnotatedMethod(
                "FooInstance", "bar", false, false,
                List.of(new Parameter("x", "I"), new Parameter("y", "I")),
                "I", List.of(), Opcodes.ACC_PUBLIC);
        final MergeTree tree = linearTree(method);

        final List<String> notes = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        new BytecodeInjector().inject(classFile, List.of(tree), mockEnv(notes, errors));

        assertTrue(errors.isEmpty(), "no errors expected: " + errors);
        assertTrue(hasZeroParamMethod(classFile, "bar"),
                "FooInstance must have a parameterless bar() after injection");
    }

    @Test
    void inject_staticMethod_addsStaticParameterlessOverload(@TempDir final Path tempDir) throws Exception {
        final String source = "public class FooStatic { public static int compute(int a, int b) { return a * b; } }";
        final Path classFile = compileClass("FooStatic", source, tempDir);

        final AnnotatedMethod method = new AnnotatedMethod(
                "FooStatic", "compute", true, false,
                List.of(new Parameter("a", "I"), new Parameter("b", "I")),
                "I", List.of(), Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        final MergeTree tree = linearTree(method);

        final List<String> notes = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        new BytecodeInjector().inject(classFile, List.of(tree), mockEnv(notes, errors));

        assertTrue(errors.isEmpty(), "no errors expected: " + errors);
        assertTrue(hasZeroParamMethod(classFile, "compute"),
                "FooStatic must have a parameterless compute() after injection");

        final int access = zeroParamMethodAccess(classFile, "compute");
        assertTrue((access & Opcodes.ACC_STATIC) != 0, "parameterless compute() must be static");
    }

    @Test
    void inject_idempotency_runningTwiceDoesNotDuplicateMethod(@TempDir final Path tempDir) throws Exception {
        final String source = "public class FooIdempotent { public int bar(int x, int y) { return x + y; } }";
        final Path classFile = compileClass("FooIdempotent", source, tempDir);

        final AnnotatedMethod method = new AnnotatedMethod(
                "FooIdempotent", "bar", false, false,
                List.of(new Parameter("x", "I"), new Parameter("y", "I")),
                "I", List.of(), Opcodes.ACC_PUBLIC);
        final MergeTree tree = linearTree(method);

        final List<String> notes = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final ProcessingEnvironment env = mockEnv(notes, errors);

        new BytecodeInjector().inject(classFile, List.of(tree), env);
        assertTrue(errors.isEmpty(), "no errors on first injection: " + errors);

        new BytecodeInjector().inject(classFile, List.of(tree), env);
        assertTrue(errors.isEmpty(), "no errors on second injection: " + errors);

        final long count = readMethods(classFile).getOrDefault("bar", List.of()).stream()
                .filter(d -> d.startsWith("()")).count();
        assertEquals(1, count, "must have exactly one parameterless bar() after two injections");
    }

    @Test
    void inject_emitsNoteOnSuccess(@TempDir final Path tempDir) throws Exception {
        final String source = "public class FooNote { public void process(int x) {} }";
        final Path classFile = compileClass("FooNote", source, tempDir);

        final AnnotatedMethod method = new AnnotatedMethod(
                "FooNote", "process", false, false,
                List.of(new Parameter("x", "I")), "V", List.of(), Opcodes.ACC_PUBLIC);
        final MergeTree tree = linearTree(method);

        final List<String> notes = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        new BytecodeInjector().inject(classFile, List.of(tree), mockEnv(notes, errors));

        assertTrue(errors.isEmpty(), "no errors expected");
        assertFalse(notes.isEmpty(), "must emit at least one NOTE on success");
    }

    @Test
    void inject_emitsErrorWhenClassFileNotFound(@TempDir final Path tempDir) throws Exception {
        final Path nonExistent = tempDir.resolve("NonExistent.class");

        final AnnotatedMethod method = new AnnotatedMethod(
                "NonExistent", "bar", false, false,
                List.of(new Parameter("x", "I")), "V", List.of(), Opcodes.ACC_PUBLIC);
        final MergeTree tree = linearTree(method);

        final List<String> notes = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        new BytecodeInjector().inject(nonExistent, List.of(tree), mockEnv(notes, errors));

        assertFalse(errors.isEmpty(), "must emit ERROR when class file not found");
    }

    @Test
    void inject_emptyTreeList_doesNothing(@TempDir final Path tempDir) throws Exception {
        final String source = "public class FooEmpty { public void bar(int x) {} }";
        final Path classFile = compileClass("FooEmpty", source, tempDir);
        final byte[] originalBytes = Files.readAllBytes(classFile);

        final List<String> notes = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        new BytecodeInjector().inject(classFile, List.of(), mockEnv(notes, errors));

        assertArrayEquals(originalBytes, Files.readAllBytes(classFile),
                "class file must be unchanged for empty tree list");
        assertTrue(errors.isEmpty(), "no errors for empty tree list");
    }

    @Test
    void inject_instanceMethod_returnTypeIsCallerClass(@TempDir final Path tempDir) throws Exception {
        final String source = "public class FooReturnType { public int bar(int x, int y) { return x + y; } }";
        final Path classFile = compileClass("FooReturnType", source, tempDir);

        final AnnotatedMethod method = new AnnotatedMethod(
                "FooReturnType", "bar", false, false,
                List.of(new Parameter("x", "I"), new Parameter("y", "I")),
                "I", List.of(), Opcodes.ACC_PUBLIC);
        final MergeTree tree = linearTree(method);

        final List<String> notes = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        new BytecodeInjector().inject(classFile, List.of(tree), mockEnv(notes, errors));

        assertTrue(errors.isEmpty(), "no errors expected: " + errors);

        final String descriptor = zeroParamMethodDescriptor(classFile, "bar");
        assertNotNull(descriptor, "parameterless bar() must exist");
        // The return type is the caller class (default package, no generated/ prefix)
        assertEquals("()LFooReturnTypeBarInvoker;", descriptor,
                "return type must be the caller class");
    }

    // =========================================================================
    // resolveEntryPointName — static helper tests
    // =========================================================================

    @Test
    void resolveEntryPointName_constructorAnnotationGroup_returnsConstructor() {
        final AnnotatedMethod m = new AnnotatedMethod(
                "com/example/Foo", "<init>", false, true, true,
                List.of(new Parameter("x", "I")), "V", List.of(), Opcodes.ACC_PUBLIC);
        final MergeTree tree = linearTree(m);
        assertEquals("constructor", BytecodeInjector.resolveEntryPointName(tree));
    }

    @Test
    void resolveEntryPointName_invokerOnConstructor_returnsLowercasedSimpleName() {
        final AnnotatedMethod m = new AnnotatedMethod(
                "com/example/Foo", "<init>", false, true, false,
                List.of(new Parameter("x", "I")), "V", List.of(), Opcodes.ACC_PUBLIC);
        final MergeTree tree = linearTree(m);
        assertEquals("foo", BytecodeInjector.resolveEntryPointName(tree));
    }

    @Test
    void resolveEntryPointName_invokerOnConstructor_defaultPackage_returnsLowercasedSimpleName() {
        // Enclosing class has no '/' — the "no slash" branch in resolveEntryPointName
        final AnnotatedMethod m = new AnnotatedMethod(
                "Foo", "<init>", false, true, false,
                List.of(new Parameter("x", "I")), "V", List.of(), Opcodes.ACC_PUBLIC);
        final MergeTree tree = linearTree(m);
        assertEquals("foo", BytecodeInjector.resolveEntryPointName(tree));
    }

    @Test
    void resolveEntryPointName_invokerOnMethod_returnsGroupName() {
        final AnnotatedMethod m = new AnnotatedMethod(
                "com/example/Foo", "compute", false, false,
                List.of(new Parameter("x", "I")), "I", List.of(), Opcodes.ACC_PUBLIC);
        final MergeTree tree = linearTree(m);
        assertEquals("compute", BytecodeInjector.resolveEntryPointName(tree));
    }

    // =========================================================================
    // resolveEntryPointAccessFlags — new method from this PR
    // =========================================================================

    @Test
    void resolveEntryPointAccessFlags_constructorAnnotationGroup_returnsPublicStatic() {
        final AnnotatedMethod m = new AnnotatedMethod(
                "com/example/Foo", "<init>", false, true, true,
                List.of(new Parameter("x", "I")), "V", List.of(), Opcodes.ACC_PUBLIC);
        final MergeTree tree = linearTree(m);
        final long flags = BytecodeInjector.resolveEntryPointAccessFlags(tree);
        assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, flags);
    }

    @Test
    void resolveEntryPointAccessFlags_invokerOnConstructor_returnsPublicStatic() {
        // @Invoker on constructor (isConstructorGroup=true, isConstructorAnnotation=false)
        final AnnotatedMethod m = new AnnotatedMethod(
                "com/example/Foo", "<init>", false, true, false,
                List.of(new Parameter("x", "I")), "V", List.of(), Opcodes.ACC_PUBLIC);
        final MergeTree tree = linearTree(m);
        final long flags = BytecodeInjector.resolveEntryPointAccessFlags(tree);
        assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, flags);
    }

    @Test
    void resolveEntryPointAccessFlags_staticInvokerMethod_returnsPublicStatic() {
        final AnnotatedMethod m = new AnnotatedMethod(
                "com/example/Foo", "compute", true, false,
                List.of(new Parameter("x", "I")), "I", List.of(),
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        final MergeTree tree = linearTree(m);
        final long flags = BytecodeInjector.resolveEntryPointAccessFlags(tree);
        assertTrue((flags & Opcodes.ACC_STATIC) != 0, "static invoker must produce static entry-point");
        assertTrue((flags & Opcodes.ACC_PUBLIC) != 0, "static public invoker must produce public entry-point");
    }

    @Test
    void resolveEntryPointAccessFlags_instanceInvokerMethod_returnsPublicNoStatic() {
        final AnnotatedMethod m = new AnnotatedMethod(
                "com/example/Foo", "bar", false, false,
                List.of(new Parameter("x", "I")), "I", List.of(), Opcodes.ACC_PUBLIC);
        final MergeTree tree = linearTree(m);
        final long flags = BytecodeInjector.resolveEntryPointAccessFlags(tree);
        assertTrue((flags & Opcodes.ACC_PUBLIC) != 0, "public invoker must produce public entry-point");
        assertEquals(0, flags & Opcodes.ACC_STATIC, "instance invoker must not be static");
    }

    @Test
    void resolveEntryPointAccessFlags_packagePrivateInstanceInvoker_returnsNoPublicNoStatic() {
        // Package-private invoker: accessFlags=0
        final AnnotatedMethod m = new AnnotatedMethod(
                "com/example/Foo", "bar", false, false,
                List.of(new Parameter("x", "I")), "I", List.of(), 0);
        final MergeTree tree = linearTree(m);
        final long flags = BytecodeInjector.resolveEntryPointAccessFlags(tree);
        assertEquals(0, flags & Opcodes.ACC_PUBLIC, "package-private invoker must not be public");
        assertEquals(0, flags & Opcodes.ACC_STATIC, "package-private invoker must not be static");
    }

    // =========================================================================
    // inject — error-path coverage (invalid class files)
    // =========================================================================

    @Test
    void inject_emitsErrorWhenClassFileIsInvalidBytecode(@TempDir final Path tempDir) throws Exception {
        final AnnotatedMethod method = new AnnotatedMethod(
                "FooDir", "bar", false, false,
                List.of(new Parameter("x", "I")), "I", List.of(), Opcodes.ACC_PUBLIC);

        // Write a regular file with invalid contents so injection fails while reading/parsing bytecode.
        final Path badClassFile = tempDir.resolve("BadClass.class");
        java.nio.file.Files.writeString(badClassFile, "not valid bytecode");

        final List<String> notes = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        new BytecodeInjector().inject(badClassFile, List.of(linearTree(method)), mockEnv(notes, errors));

        assertFalse(errors.isEmpty(), "must emit ERROR for invalid class file");
    }
}
