package rg.rawit.processors.codegen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rg.rawit.processors.model.*;
import rg.rawit.processors.model.MergeNode.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JavaPoetGenerator}.
 *
 * <p>Uses a mock {@link Filer} to capture written source content and assert on it.
 */
class JavaPoetGeneratorTest {

    // -------------------------------------------------------------------------
    // Mock infrastructure
    // -------------------------------------------------------------------------

    /** Captures all written JavaFile content keyed by class name. */
    private final Map<String, String> written = new LinkedHashMap<>();
    private final List<String> notes = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    private Filer mockFiler;
    private Messager mockMessager;
    private ProcessingEnvironment mockEnv;

    @BeforeEach
    void setUp() {
        written.clear();
        notes.clear();
        errors.clear();

        mockFiler = new Filer() {
            @Override
            public JavaFileObject createSourceFile(CharSequence name, Element... originatingElements) throws IOException {
                final String className = name.toString();
                return new SimpleJavaFileObject(URI.create("string:///" + className.replace('.', '/') + ".java"),
                        JavaFileObject.Kind.SOURCE) {
                    @Override
                    public Writer openWriter() {
                        return new StringWriter() {
                            @Override
                            public void close() throws IOException {
                                super.close();
                                written.put(className, toString());
                            }
                        };
                    }
                };
            }

            @Override
            public JavaFileObject createClassFile(CharSequence name, Element... originatingElements) throws IOException {
                throw new UnsupportedOperationException();
            }

            @Override
            public FileObject createResource(JavaFileManager.Location location, CharSequence moduleAndPkg,
                                             CharSequence relativeName, Element... originatingElements) throws IOException {
                throw new UnsupportedOperationException();
            }

            @Override
            public FileObject getResource(JavaFileManager.Location location, CharSequence moduleAndPkg,
                                          CharSequence relativeName) throws IOException {
                throw new UnsupportedOperationException();
            }
        };

        mockMessager = new Messager() {
            @Override public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
                if (kind == Diagnostic.Kind.NOTE) notes.add(msg.toString());
                if (kind == Diagnostic.Kind.ERROR) errors.add(msg.toString());
            }
            @Override public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) { printMessage(kind, msg); }
            @Override public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) { printMessage(kind, msg); }
            @Override public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a, AnnotationValue v) { printMessage(kind, msg); }
        };

        mockEnv = new ProcessingEnvironment() {
            @Override public Map<String, String> getOptions() { return Map.of(); }
            @Override public Messager getMessager() { return mockMessager; }
            @Override public Filer getFiler() { return mockFiler; }
            @Override public Elements getElementUtils() { return null; }
            @Override public Types getTypeUtils() { return null; }
            @Override public SourceVersion getSourceVersion() { return SourceVersion.RELEASE_21; }
            @Override public Locale getLocale() { return Locale.getDefault(); }
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static AnnotatedMethod method(String name, Parameter... params) {
        return new AnnotatedMethod("com/example/Foo", name, false, false,
                List.of(params), "V", List.of());
    }

    private static Parameter p(String name, String type) {
        return new Parameter(name, type);
    }

    private static MergeTree linearTree(AnnotatedMethod m) {
        OverloadGroup g = new OverloadGroup(m.enclosingClassName(), m.methodName(), List.of(m));
        MergeNode root = buildChain(m.parameters(), 0, m);
        return new MergeTree(g, root);
    }

    private static MergeNode buildChain(List<Parameter> params, int pos, AnnotatedMethod m) {
        if (pos == params.size()) return new TerminalNode(List.of(m), null);
        return new SharedNode(params.get(pos).name(), params.get(pos).typeDescriptor(),
                buildChain(params, pos + 1, m));
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void generate_writesSourceFileForTree() {
        AnnotatedMethod m = method("bar", p("x", "I"), p("y", "I"));
        MergeTree tree = linearTree(m);

        new JavaPoetGenerator(mockMessager).generate(List.of(tree), mockEnv);

        assertFalse(written.isEmpty(), "at least one source file must be written");
    }

    @Test
    void generate_emitsNoteOnSuccess() {
        AnnotatedMethod m = method("bar", p("x", "I"));
        MergeTree tree = linearTree(m);

        new JavaPoetGenerator(mockMessager).generate(List.of(tree), mockEnv);

        assertFalse(notes.isEmpty(), "must emit a NOTE on successful generation");
        assertTrue(notes.stream().anyMatch(n -> n.contains("Bar")),
                "NOTE must mention the generated class name");
    }

    @Test
    void generate_writtenSourceContainsCallerClass() {
        AnnotatedMethod m = method("bar", p("x", "I"), p("y", "I"));
        MergeTree tree = linearTree(m);

        new JavaPoetGenerator(mockMessager).generate(List.of(tree), mockEnv);

        String allSource = String.join("\n", written.values());
        assertTrue(allSource.contains("class Bar"), "generated source must contain the Bar class");
    }

    @Test
    void generate_writtenSourceContainsStageInterfaces() {
        AnnotatedMethod m = method("bar", p("x", "I"), p("y", "I"));
        MergeTree tree = linearTree(m);

        new JavaPoetGenerator(mockMessager).generate(List.of(tree), mockEnv);

        String allSource = String.join("\n", written.values());
        assertTrue(allSource.contains("XStageInvoker"), "must contain XStageInvoker");
        assertTrue(allSource.contains("YStageInvoker"), "must contain YStageInvoker");
        assertTrue(allSource.contains("InvokeStageInvoker"), "must contain InvokeStageInvoker");
    }

    @Test
    void generate_idempotency_secondCallLogsNoteNotError() {
        AnnotatedMethod m = method("bar", p("x", "I"));
        MergeTree tree = linearTree(m);
        JavaPoetGenerator generator = new JavaPoetGenerator(mockMessager);

        // First call succeeds
        generator.generate(List.of(tree), mockEnv);

        // Second call with a filer that throws FilerException
        final Filer idempotentFiler = new Filer() {
            @Override
            public JavaFileObject createSourceFile(CharSequence name, Element... originatingElements) throws IOException {
                throw new FilerException("File already exists: " + name);
            }
            @Override public JavaFileObject createClassFile(CharSequence name, Element... originatingElements) throws IOException { throw new UnsupportedOperationException(); }
            @Override public FileObject createResource(JavaFileManager.Location l, CharSequence m2, CharSequence r, Element... e) throws IOException { throw new UnsupportedOperationException(); }
            @Override public FileObject getResource(JavaFileManager.Location l, CharSequence m2, CharSequence r) throws IOException { throw new UnsupportedOperationException(); }
        };

        ProcessingEnvironment idempotentEnv = new ProcessingEnvironment() {
            @Override public Map<String, String> getOptions() { return Map.of(); }
            @Override public Messager getMessager() { return mockMessager; }
            @Override public Filer getFiler() { return idempotentFiler; }
            @Override public Elements getElementUtils() { return null; }
            @Override public Types getTypeUtils() { return null; }
            @Override public SourceVersion getSourceVersion() { return SourceVersion.RELEASE_21; }
            @Override public Locale getLocale() { return Locale.getDefault(); }
        };

        // Should not throw, should log NOTE
        assertDoesNotThrow(() -> generator.generate(List.of(tree), idempotentEnv));
        assertTrue(errors.isEmpty(), "second call must not emit ERROR");
        assertTrue(notes.stream().anyMatch(n -> n.contains("Skipping") || n.contains("already")),
                "second call must log a NOTE about skipping");
    }

    @Test
    void generate_multipleTrees_writesOneFilePerTree() {
        AnnotatedMethod m1 = method("bar", p("x", "I"));
        AnnotatedMethod m2 = method("compute", p("a", "I"));
        List<MergeTree> trees = List.of(linearTree(m1), linearTree(m2));

        new JavaPoetGenerator(mockMessager).generate(trees, mockEnv);

        // Two separate files should be written
        assertEquals(2, written.size(), "must write one file per tree");
    }

    @Test
    void generate_emptyTrees_writesNothing() {
        new JavaPoetGenerator(mockMessager).generate(List.of(), mockEnv);

        assertTrue(written.isEmpty(), "no files written for empty tree list");
        assertTrue(errors.isEmpty(), "no errors for empty tree list");
    }
}
