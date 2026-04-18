package rawit.processors.tagged;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rawit.processors.RawitAnnotationProcessor;

import javax.tools.*;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for the {@code @TaggedValue} analyzer pipeline.
 *
 * <p>Each test compiles in-memory Java source files that define tag annotations
 * (annotated with {@code @TaggedValue}) and usage code, then verifies the
 * expected warnings are emitted by the {@link RawitAnnotationProcessor}.
 *
 * <p>Validates: Requirements 3.1, 3.2, 4.1, 5.1, 6.1, 7.1, 7.2, 8.1, 9.1,
 * 10.1, 10.2, 11.1, 11.2, 12.1, 12.2, 12.3, 13.1, 13.2, 13.3
 */
class TaggedValueAnalyzerIntegrationTest {

    // =========================================================================
    // Infrastructure helpers
    // =========================================================================

    /**
     * Compiles multiple in-memory source files with the RawitAnnotationProcessor
     * and returns all diagnostics.
     */
    private static List<Diagnostic<? extends JavaFileObject>> compileWithProcessor(
            final List<String> classNames,
            final List<String> sources,
            final Path outputDir
    ) throws Exception {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JavaCompiler not available — run tests with a JDK, not a JRE");

        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (final StandardJavaFileManager fm =
                     compiler.getStandardFileManager(diagnostics, null, null)) {

            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
            fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(outputDir.toFile()));

            final List<JavaFileObject> sourceFiles = new ArrayList<>();
            for (int i = 0; i < classNames.size(); i++) {
                final String cn = classNames.get(i);
                final String src = sources.get(i);
                sourceFiles.add(new SimpleJavaFileObject(
                        URI.create("string:///" + cn.replace('.', '/') + ".java"),
                        JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return src;
                    }
                });
            }

            final String classpath = outputDir.toAbsolutePath()
                    + File.pathSeparator
                    + System.getProperty("java.class.path", "");

            final JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fm, diagnostics,
                    List.of("-classpath", classpath),
                    null, sourceFiles);
            task.setProcessors(List.of(new RawitAnnotationProcessor()));
            task.call();
        }
        return diagnostics.getDiagnostics();
    }

    /** Extracts warning messages from diagnostics. */
    private static List<String> warningMessages(
            final List<Diagnostic<? extends JavaFileObject>> diagnostics
    ) {
        return diagnostics.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
                .map(d -> d.getMessage(null))
                .collect(Collectors.toList());
    }

    /** Asserts that no diagnostic has kind ERROR. */
    private static void assertNoErrors(
            final List<Diagnostic<? extends JavaFileObject>> diagnostics
    ) {
        final List<String> errors = diagnostics.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(null))
                .collect(Collectors.toList());
        assertTrue(errors.isEmpty(), "Expected no errors, but got: " + errors);
    }

    /** Asserts all diagnostics of interest are WARNING (not ERROR). */
    private static void assertAllWarningsNotErrors(
            final List<Diagnostic<? extends JavaFileObject>> diagnostics
    ) {
        for (final Diagnostic<? extends JavaFileObject> d : diagnostics) {
            if (d.getMessage(null).contains("tag mismatch")
                    || d.getMessage(null).contains("strict")
                    || d.getMessage(null).contains("multiple tag")) {
                assertEquals(Diagnostic.Kind.WARNING, d.getKind(),
                        "Expected WARNING but got " + d.getKind() + " for: " + d.getMessage(null));
            }
        }
    }

    // =========================================================================
    // Common source fragments
    // =========================================================================

    private static final String USER_ID_SOURCE =
            "package testpkg;\n" +
            "import rawit.TaggedValue;\n" +
            "import java.lang.annotation.*;\n" +
            "@TaggedValue(strict = true)\n" +
            "@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE})\n" +
            "@Retention(RetentionPolicy.CLASS)\n" +
            "public @interface UserId { }\n";

    private static final String FIRST_NAME_SOURCE =
            "package testpkg;\n" +
            "import rawit.TaggedValue;\n" +
            "import java.lang.annotation.*;\n" +
            "@TaggedValue\n" +
            "@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE})\n" +
            "@Retention(RetentionPolicy.CLASS)\n" +
            "public @interface FirstName { }\n";

    private static final String LAST_NAME_SOURCE =
            "package testpkg;\n" +
            "import rawit.TaggedValue;\n" +
            "import java.lang.annotation.*;\n" +
            "@TaggedValue\n" +
            "@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE})\n" +
            "@Retention(RetentionPolicy.CLASS)\n" +
            "public @interface LastName { }\n";


    // =========================================================================
    // Test 1 — Tag mismatch: @FirstName → @LastName assignment
    // Validates: Requirements 7.1, 7.2, 12.1, 12.3
    // =========================================================================

    @Test
    void tagMismatch_firstNameToLastName_emitsWarning(@TempDir final Path outputDir)
            throws Exception {
        final String usageSource =
                "package testpkg;\n" +
                "public class TagMismatchTest {\n" +
                "    void test() {\n" +
                "        @FirstName String first = \"John\";\n" +
                "        @LastName String last = first;\n" +
                "    }\n" +
                "}\n";

        final List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithProcessor(
                List.of("testpkg.FirstName", "testpkg.LastName", "testpkg.TagMismatchTest"),
                List.of(FIRST_NAME_SOURCE, LAST_NAME_SOURCE, usageSource),
                outputDir);

        assertNoErrors(diagnostics);
        assertAllWarningsNotErrors(diagnostics);

        final List<String> warnings = warningMessages(diagnostics);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("tag mismatch")),
                "Expected tag mismatch warning, got: " + warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("FirstName") && w.contains("LastName")),
                "Warning should mention both FirstName and LastName, got: " + warnings);
    }

    // =========================================================================
    // Test 2 — Strict untagged→tagged: rawId → @UserId
    // Validates: Requirements 3.1, 12.2, 12.3
    // =========================================================================

    @Test
    void strictUntaggedToTagged_rawIdToUserId_emitsWarning(@TempDir final Path outputDir)
            throws Exception {
        final String usageSource =
                "package testpkg;\n" +
                "public class StrictUntaggedTest {\n" +
                "    long getRawId() { return 42L; }\n" +
                "    void test() {\n" +
                "        long rawId = getRawId();\n" +
                "        @UserId long taggedId = rawId;\n" +
                "    }\n" +
                "}\n";

        final List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithProcessor(
                List.of("testpkg.UserId", "testpkg.StrictUntaggedTest"),
                List.of(USER_ID_SOURCE, usageSource),
                outputDir);

        assertNoErrors(diagnostics);
        assertAllWarningsNotErrors(diagnostics);

        final List<String> warnings = warningMessages(diagnostics);
        assertTrue(warnings.stream().anyMatch(w ->
                        w.contains("untagged") && w.contains("strict") && w.contains("UserId")),
                "Expected strict untagged→tagged warning mentioning UserId, got: " + warnings);
    }

    // =========================================================================
    // Test 3 — No warning for literal → strict tagged
    // Validates: Requirements 3.2
    // =========================================================================

    @Test
    void literalToStrictTagged_noWarning(@TempDir final Path outputDir) throws Exception {
        final String usageSource =
                "package testpkg;\n" +
                "public class LiteralExemptTest {\n" +
                "    void test() {\n" +
                "        @UserId long taggedId = 42L;\n" +
                "    }\n" +
                "}\n";

        final List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithProcessor(
                List.of("testpkg.UserId", "testpkg.LiteralExemptTest"),
                List.of(USER_ID_SOURCE, usageSource),
                outputDir);

        assertNoErrors(diagnostics);

        final List<String> warnings = warningMessages(diagnostics);
        assertTrue(warnings.stream().noneMatch(w ->
                        w.contains("untagged") && w.contains("UserId")),
                "Expected no warning for literal → strict tagged, got: " + warnings);
    }

    // =========================================================================
    // Test 4 — No warning for lax tagged → untagged
    // Validates: Requirements 6.1
    // =========================================================================

    @Test
    void laxTaggedToUntagged_noWarning(@TempDir final Path outputDir) throws Exception {
        final String usageSource =
                "package testpkg;\n" +
                "public class LaxToUntaggedTest {\n" +
                "    void test() {\n" +
                "        @FirstName String tagged = \"John\";\n" +
                "        String untagged = tagged;\n" +
                "    }\n" +
                "}\n";

        final List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithProcessor(
                List.of("testpkg.FirstName", "testpkg.LaxToUntaggedTest"),
                List.of(FIRST_NAME_SOURCE, usageSource),
                outputDir);

        assertNoErrors(diagnostics);

        final List<String> warnings = warningMessages(diagnostics);
        assertTrue(warnings.stream().noneMatch(w ->
                        w.contains("FirstName") && w.contains("untagged")),
                "Expected no warning for lax tagged → untagged, got: " + warnings);
    }

    // =========================================================================
    // Test 5 — No warning for same-tag assignment
    // Validates: Requirements 8.1
    // =========================================================================

    @Test
    void sameTagAssignment_noWarning(@TempDir final Path outputDir) throws Exception {
        final String usageSource =
                "package testpkg;\n" +
                "public class SameTagTest {\n" +
                "    void test() {\n" +
                "        @FirstName String name1 = \"John\";\n" +
                "        @FirstName String name2 = name1;\n" +
                "    }\n" +
                "}\n";

        final List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithProcessor(
                List.of("testpkg.FirstName", "testpkg.SameTagTest"),
                List.of(FIRST_NAME_SOURCE, usageSource),
                outputDir);

        assertNoErrors(diagnostics);

        final List<String> warnings = warningMessages(diagnostics);
        assertTrue(warnings.stream().noneMatch(w ->
                        w.contains("mismatch") || w.contains("strict")),
                "Expected no warning for same-tag assignment, got: " + warnings);
    }

    // =========================================================================
    // Test 6 — No warning for untagged → untagged
    // Validates: Requirements 9.1
    // =========================================================================

    @Test
    void untaggedToUntagged_noWarning(@TempDir final Path outputDir) throws Exception {
        final String usageSource =
                "package testpkg;\n" +
                "public class UntaggedTest {\n" +
                "    void test() {\n" +
                "        String a = \"hello\";\n" +
                "        String b = a;\n" +
                "    }\n" +
                "}\n";

        // Include a tag annotation so the processor activates, but usage has no tags
        final List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithProcessor(
                List.of("testpkg.FirstName", "testpkg.UntaggedTest"),
                List.of(FIRST_NAME_SOURCE, usageSource),
                outputDir);

        assertNoErrors(diagnostics);

        final List<String> warnings = warningMessages(diagnostics);
        assertTrue(warnings.stream().noneMatch(w ->
                        w.contains("mismatch") || w.contains("strict") || w.contains("untagged")),
                "Expected no warning for untagged → untagged, got: " + warnings);
    }

    // =========================================================================
    // Test 7 — Multiple tags: first tag used, duplicate warning emitted
    // Validates: Requirements 11.1, 11.2
    // =========================================================================

    @Test
    void multipleTags_firstTagUsed_duplicateWarningEmitted(@TempDir final Path outputDir)
            throws Exception {
        // Define a tag annotation that allows multiple targets
        final String multiTagSource =
                "package testpkg;\n" +
                "import rawit.TaggedValue;\n" +
                "import java.lang.annotation.*;\n" +
                "@TaggedValue\n" +
                "@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE})\n" +
                "@Retention(RetentionPolicy.CLASS)\n" +
                "public @interface NickName { }\n";

        final String usageSource =
                "package testpkg;\n" +
                "public class MultiTagTest {\n" +
                "    void test() {\n" +
                "        @FirstName @NickName String name = \"John\";\n" +
                "        @FirstName String first = name;\n" +
                "    }\n" +
                "}\n";

        final List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithProcessor(
                List.of("testpkg.FirstName", "testpkg.NickName", "testpkg.MultiTagTest"),
                List.of(FIRST_NAME_SOURCE, multiTagSource, usageSource),
                outputDir);

        assertNoErrors(diagnostics);
        assertAllWarningsNotErrors(diagnostics);

        final List<String> warnings = warningMessages(diagnostics);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("multiple tag")),
                "Expected duplicate-tag warning, got: " + warnings);
    }

    // =========================================================================
    // Test 8 — All warnings use Diagnostic.Kind.WARNING (not ERROR)
    // Validates: Requirements 12.3
    // =========================================================================

    @Test
    void allTagWarnings_useWarningKind_notError(@TempDir final Path outputDir) throws Exception {
        final String usageSource =
                "package testpkg;\n" +
                "public class AllWarningsTest {\n" +
                "    long getRawId() { return 1L; }\n" +
                "    void test() {\n" +
                "        @FirstName String first = \"John\";\n" +
                "        @LastName String last = first;\n" +       // tag mismatch
                "        long rawId = getRawId();\n" +
                "        @UserId long tagged = rawId;\n" +         // strict untagged→tagged
                "    }\n" +
                "}\n";

        final List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithProcessor(
                List.of("testpkg.UserId", "testpkg.FirstName", "testpkg.LastName",
                        "testpkg.AllWarningsTest"),
                List.of(USER_ID_SOURCE, FIRST_NAME_SOURCE, LAST_NAME_SOURCE, usageSource),
                outputDir);

        assertNoErrors(diagnostics);
        assertAllWarningsNotErrors(diagnostics);

        // Verify we got at least the expected warnings
        final List<String> warnings = warningMessages(diagnostics);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("tag mismatch")),
                "Expected tag mismatch warning, got: " + warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("untagged") && w.contains("strict")),
                "Expected strict untagged→tagged warning, got: " + warnings);
    }

    // =========================================================================
    // Test 9 — No warning for untagged → lax tagged
    // Validates: Requirements 4.1
    // =========================================================================

    @Test
    void untaggedToLaxTagged_noWarning(@TempDir final Path outputDir) throws Exception {
        final String usageSource =
                "package testpkg;\n" +
                "public class UntaggedToLaxTest {\n" +
                "    String getRawName() { return \"John\"; }\n" +
                "    void test() {\n" +
                "        String rawName = getRawName();\n" +
                "        @FirstName String taggedName = rawName;\n" +
                "    }\n" +
                "}\n";

        final List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithProcessor(
                List.of("testpkg.FirstName", "testpkg.UntaggedToLaxTest"),
                List.of(FIRST_NAME_SOURCE, usageSource),
                outputDir);

        assertNoErrors(diagnostics);

        final List<String> warnings = warningMessages(diagnostics);
        assertTrue(warnings.stream().noneMatch(w ->
                        w.contains("untagged") && w.contains("FirstName")),
                "Expected no warning for untagged → lax tagged, got: " + warnings);
    }

    // =========================================================================
    // Test 10 — Strict tagged → untagged emits warning
    // Validates: Requirements 5.1
    // =========================================================================

    @Test
    void strictTaggedToUntagged_emitsWarning(@TempDir final Path outputDir) throws Exception {
        final String usageSource =
                "package testpkg;\n" +
                "public class StrictToUntaggedTest {\n" +
                "    void test() {\n" +
                "        @UserId long taggedId = 42L;\n" +
                "        long rawId = taggedId;\n" +
                "    }\n" +
                "}\n";

        final List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithProcessor(
                List.of("testpkg.UserId", "testpkg.StrictToUntaggedTest"),
                List.of(USER_ID_SOURCE, usageSource),
                outputDir);

        assertNoErrors(diagnostics);
        assertAllWarningsNotErrors(diagnostics);

        final List<String> warnings = warningMessages(diagnostics);
        assertTrue(warnings.stream().anyMatch(w ->
                        w.contains("UserId") && w.contains("strict") && w.contains("untagged")),
                "Expected strict tagged→untagged warning mentioning UserId, got: " + warnings);
    }

    // =========================================================================
    // Test 11 — Builder stage method chain: tag checking applies to stage method arguments
    // NOTE: This test uses a hand-written builder with manually tagged parameters
    // to validate the analyzer's method-invocation argument checking. Rawit's
    // codegen does not currently propagate tag annotations onto generated stage
    // method parameters, so this test does not cover the generated-chain case.
    // Validates: Requirements 10.1, 10.2
    // =========================================================================

    @Test
    void builderStageMethodChain_warnsOnTagMismatch(
            @TempDir final Path outputDir) throws Exception {
        // Hand-written builder with manually tagged parameters to validate
        // the analyzer's method-invocation argument checking.
        final String userSource =
                "package testpkg;\n" +
                "public class User {\n" +
                "    public static LastNameStage constructor() {\n" +
                "        return new Builder();\n" +
                "    }\n" +
                "    public interface LastNameStage {\n" +
                "        UserIdStage lastName(@LastName String lastName);\n" +
                "    }\n" +
                "    public interface UserIdStage {\n" +
                "        BuildStage id(@UserId long userId);\n" +
                "    }\n" +
                "    public interface BuildStage {\n" +
                "        User build();\n" +
                "    }\n" +
                "    static class Builder implements LastNameStage, UserIdStage, BuildStage {\n" +
                "        public UserIdStage lastName(@LastName String lastName) { return this; }\n" +
                "        public BuildStage id(@UserId long userId) { return this; }\n" +
                "        public User build() { return new User(); }\n" +
                "    }\n" +
                "}\n";

        final String usageSource =
                "package testpkg;\n" +
                "public class BuilderStageMethodTest {\n" +
                "    void test() {\n" +
                "        @FirstName String first = \"John\";\n" +
                "        User.constructor().lastName(first).id(10L).build();\n" +
                "    }\n" +
                "}\n";

        final List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithProcessor(
                List.of("testpkg.UserId", "testpkg.FirstName", "testpkg.LastName",
                        "testpkg.User", "testpkg.BuilderStageMethodTest"),
                List.of(USER_ID_SOURCE, FIRST_NAME_SOURCE, LAST_NAME_SOURCE, userSource, usageSource),
                outputDir);

        assertNoErrors(diagnostics);
        assertAllWarningsNotErrors(diagnostics);

        final List<String> warnings = warningMessages(diagnostics);
        // @FirstName → @LastName parameter should produce tag mismatch
        assertTrue(warnings.stream().anyMatch(w -> w.contains("tag mismatch")),
                "Expected tag mismatch warning for builder stage method parameter, got: " + warnings);
    }

    // =========================================================================
    // Test 12 — Return statement: strict tagged return type warns on untagged return
    // Validates: Requirements 5.1
    // =========================================================================

    @Test
    void returnStatement_strictTaggedReturnType_warnsOnUntaggedReturn(
            @TempDir final Path outputDir) throws Exception {
        final String userIdMethodSource =
                "package testpkg;\n" +
                "import rawit.TaggedValue;\n" +
                "import java.lang.annotation.*;\n" +
                "@TaggedValue(strict = true)\n" +
                "@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE})\n" +
                "@Retention(RetentionPolicy.CLASS)\n" +
                "public @interface UserIdMethod { }\n";

        final String usageSource =
                "package testpkg;\n" +
                "public class ReturnStrictTest {\n" +
                "    long getRawId() { return 1L; }\n" +
                "    @UserIdMethod long getTaggedId() {\n" +
                "        long rawId = getRawId();\n" +
                "        return rawId;\n" +
                "    }\n" +
                "}\n";

        final List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithProcessor(
                List.of("testpkg.UserIdMethod", "testpkg.ReturnStrictTest"),
                List.of(userIdMethodSource, usageSource),
                outputDir);

        assertNoErrors(diagnostics);
        assertAllWarningsNotErrors(diagnostics);

        final List<String> warnings = warningMessages(diagnostics);
        assertTrue(warnings.stream().anyMatch(w ->
                        w.contains("untagged") && w.contains("strict")),
                "Expected strict untagged→tagged warning for return statement, got: " + warnings);
    }

    // =========================================================================
    // Test 13 — Return statement: literal exempt from strict tagged return type
    // Validates: Requirements 3.2
    // =========================================================================

    @Test
    void returnStatement_literalToStrictTaggedReturnType_noWarning(
            @TempDir final Path outputDir) throws Exception {
        final String userIdMethodSource =
                "package testpkg;\n" +
                "import rawit.TaggedValue;\n" +
                "import java.lang.annotation.*;\n" +
                "@TaggedValue(strict = true)\n" +
                "@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE})\n" +
                "@Retention(RetentionPolicy.CLASS)\n" +
                "public @interface UserIdMethod { }\n";

        final String usageSource =
                "package testpkg;\n" +
                "public class ReturnLiteralTest {\n" +
                "    @UserIdMethod long getDefaultId() {\n" +
                "        return 42L;\n" +
                "    }\n" +
                "}\n";

        final List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithProcessor(
                List.of("testpkg.UserIdMethod", "testpkg.ReturnLiteralTest"),
                List.of(userIdMethodSource, usageSource),
                outputDir);

        assertNoErrors(diagnostics);

        final List<String> warnings = warningMessages(diagnostics);
        assertTrue(warnings.stream().noneMatch(w ->
                        w.contains("untagged") && w.contains("UserIdMethod")),
                "Expected no warning for literal return to strict tagged, got: " + warnings);
    }

    // =========================================================================
    // Test 14 — Return statement: tag mismatch on return
    // Validates: Requirements 7.1
    // =========================================================================

    @Test
    void returnStatement_tagMismatch_emitsWarning(
            @TempDir final Path outputDir) throws Exception {
        final String firstNameMethodSource =
                "package testpkg;\n" +
                "import rawit.TaggedValue;\n" +
                "import java.lang.annotation.*;\n" +
                "@TaggedValue\n" +
                "@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE})\n" +
                "@Retention(RetentionPolicy.CLASS)\n" +
                "public @interface FirstNameMethod { }\n";

        final String lastNameMethodSource =
                "package testpkg;\n" +
                "import rawit.TaggedValue;\n" +
                "import java.lang.annotation.*;\n" +
                "@TaggedValue\n" +
                "@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE})\n" +
                "@Retention(RetentionPolicy.CLASS)\n" +
                "public @interface LastNameMethod { }\n";

        final String usageSource =
                "package testpkg;\n" +
                "public class ReturnMismatchTest {\n" +
                "    @LastNameMethod String getLastName() {\n" +
                "        @FirstNameMethod String first = \"John\";\n" +
                "        return first;\n" +
                "    }\n" +
                "}\n";

        final List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithProcessor(
                List.of("testpkg.FirstNameMethod", "testpkg.LastNameMethod", "testpkg.ReturnMismatchTest"),
                List.of(firstNameMethodSource, lastNameMethodSource, usageSource),
                outputDir);

        assertNoErrors(diagnostics);
        assertAllWarningsNotErrors(diagnostics);

        final List<String> warnings = warningMessages(diagnostics);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("tag mismatch")),
                "Expected tag mismatch warning for return statement, got: " + warnings);
    }

    // =========================================================================
    // Test 15 — Multiple-tag warning deduplication: same element used in
    //           multiple assignments should produce only one duplicate-tag warning
    // Validates: Requirements 11.1, 11.2 (dedup regression)
    // =========================================================================

    @Test
    void multipleTags_usedInMultipleAssignments_onlyOneDuplicateWarning(
            @TempDir final Path outputDir) throws Exception {
        final String nickNameSource =
                "package testpkg;\n" +
                "import rawit.TaggedValue;\n" +
                "import java.lang.annotation.*;\n" +
                "@TaggedValue\n" +
                "@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE})\n" +
                "@Retention(RetentionPolicy.CLASS)\n" +
                "public @interface NickName { }\n";

        final String usageSource =
                "package testpkg;\n" +
                "public class DedupMultiTagTest {\n" +
                "    void test() {\n" +
                "        @FirstName @NickName String name = \"John\";\n" +
                "        @FirstName String a = name;\n" +
                "        @FirstName String b = name;\n" +
                "        @FirstName String c = name;\n" +
                "    }\n" +
                "}\n";

        final List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithProcessor(
                List.of("testpkg.FirstName", "testpkg.NickName", "testpkg.DedupMultiTagTest"),
                List.of(FIRST_NAME_SOURCE, nickNameSource, usageSource),
                outputDir);

        assertNoErrors(diagnostics);

        final long multipleTagWarningCount = diagnostics.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
                .filter(d -> d.getMessage(null).contains("multiple tag"))
                .count();
        assertEquals(1, multipleTagWarningCount,
                "Expected exactly 1 duplicate-tag warning (not one per assignment), got: "
                        + multipleTagWarningCount);
    }

    // =========================================================================
    // Test 16 — Unary minus on literal assigned to strict tagged: no warning
    // Validates: Requirements 3.2 (unary constant expression exempt)
    // =========================================================================

    @Test
    void unaryMinusLiteral_toStrictTagged_noWarning(@TempDir final Path outputDir)
            throws Exception {
        final String usageSource =
                "package testpkg;\n" +
                "public class UnaryMinusTest {\n" +
                "    void test() {\n" +
                "        @UserId long id = -1L;\n" +
                "    }\n" +
                "}\n";

        final List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithProcessor(
                List.of("testpkg.UserId", "testpkg.UnaryMinusTest"),
                List.of(USER_ID_SOURCE, usageSource),
                outputDir);

        assertNoErrors(diagnostics);

        final List<String> warnings = warningMessages(diagnostics);
        assertTrue(warnings.stream().noneMatch(w ->
                        w.contains("untagged") && w.contains("UserId")),
                "Expected no warning for unary minus literal → strict tagged, got: " + warnings);
    }

    // =========================================================================
    // Test 17 — Binary constant expression assigned to strict tagged
    // Documents behavior: 1L + 2L is a binary expression, not recognized as a
    // simple literal/constant by the analyzer (javac folds it, but the AST
    // still shows a BinaryTree). This test documents that a warning IS emitted.
    // =========================================================================

    @Test
    void binaryConstantExpression_toStrictTagged_emitsWarning(@TempDir final Path outputDir)
            throws Exception {
        final String usageSource =
                "package testpkg;\n" +
                "public class BinaryConstExprTest {\n" +
                "    void test() {\n" +
                "        @UserId long id = 1L + 2L;\n" +
                "    }\n" +
                "}\n";

        final List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithProcessor(
                List.of("testpkg.UserId", "testpkg.BinaryConstExprTest"),
                List.of(USER_ID_SOURCE, usageSource),
                outputDir);

        assertNoErrors(diagnostics);

        // Binary constant expressions (1L + 2L) are not recognized as literals
        // by the analyzer's isLiteralOrConstant check — the AST shows a BinaryTree,
        // not a LiteralTree. A warning IS expected here.
        final List<String> warnings = warningMessages(diagnostics);
        assertTrue(warnings.stream().anyMatch(w ->
                        w.contains("untagged") && w.contains("UserId")),
                "Expected warning for binary expression → strict tagged (not recognized as constant), got: "
                        + warnings);
    }

    // =========================================================================
    // Test 18 — Static final constant assigned to strict tagged: no warning
    // Validates: Requirements 3.2 (compile-time constant exempt)
    // =========================================================================

    @Test
    void staticFinalConstant_toStrictTagged_noWarning(@TempDir final Path outputDir)
            throws Exception {
        final String usageSource =
                "package testpkg;\n" +
                "public class StaticFinalConstTest {\n" +
                "    static final long CONST = 42L;\n" +
                "    void test() {\n" +
                "        @UserId long id = CONST;\n" +
                "    }\n" +
                "}\n";

        final List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithProcessor(
                List.of("testpkg.UserId", "testpkg.StaticFinalConstTest"),
                List.of(USER_ID_SOURCE, usageSource),
                outputDir);

        assertNoErrors(diagnostics);

        final List<String> warnings = warningMessages(diagnostics);
        assertTrue(warnings.stream().noneMatch(w ->
                        w.contains("untagged") && w.contains("UserId")),
                "Expected no warning for static final constant → strict tagged, got: " + warnings);
    }

    // =========================================================================
    // Test 19 — Final local variable with constant value assigned to strict tagged
    // Documents behavior: final local variables with constant initializers may
    // or may not have getConstantValue() != null depending on the javac version.
    // This test documents the observed behavior.
    // =========================================================================

    @Test
    void finalLocalConstant_toStrictTagged_documentsConstantDetection(
            @TempDir final Path outputDir) throws Exception {
        final String usageSource =
                "package testpkg;\n" +
                "public class FinalLocalConstTest {\n" +
                "    void test() {\n" +
                "        final long localConst = 42L;\n" +
                "        @UserId long id = localConst;\n" +
                "    }\n" +
                "}\n";

        final List<Diagnostic<? extends JavaFileObject>> diagnostics = compileWithProcessor(
                List.of("testpkg.UserId", "testpkg.FinalLocalConstTest"),
                List.of(USER_ID_SOURCE, usageSource),
                outputDir);

        assertNoErrors(diagnostics);

        // Final local variables with constant initializers are recognized as
        // compile-time constants by javac (getConstantValue() returns non-null),
        // so no warning should be emitted.
        final List<String> warnings = warningMessages(diagnostics);
        assertTrue(warnings.stream().noneMatch(w ->
                        w.contains("untagged") && w.contains("UserId")),
                "Expected no warning for final local constant → strict tagged, got: " + warnings);
    }

    // =========================================================================
    // Test 20 — Generated builder chain: tag mismatch detected end-to-end
    // Validates: Requirements 10.1, 10.2, 10.3
    //
    // Two-pass compilation:
    //   Pass 1: compile tag annotations + @Constructor record → generates
    //           stage interfaces with propagated tag annotations
    //   Pass 2: compile client code that passes a @FirstName value to the
    //           generated .lastName(...) stage method → tag mismatch warning
    // =========================================================================

    @Test
    void generatedBuilderChain_propagatesTagAnnotations_warnsOnMismatch(
            @TempDir final Path outputDir) throws Exception {

        // --- Pass 1: compile tag annotations + @Constructor record ---
        final String taggedUserSource =
                "package testpkg;\n" +
                "import rawit.Constructor;\n" +
                "@Constructor\n" +
                "public record TaggedUser(\n" +
                "    @UserId long userId,\n" +
                "    @FirstName String firstName,\n" +
                "    @LastName String lastName\n" +
                ") { }\n";

        final List<Diagnostic<? extends JavaFileObject>> pass1Diagnostics = compileWithProcessor(
                List.of("testpkg.UserId", "testpkg.FirstName", "testpkg.LastName",
                        "testpkg.TaggedUser"),
                List.of(USER_ID_SOURCE, FIRST_NAME_SOURCE, LAST_NAME_SOURCE,
                        taggedUserSource),
                outputDir);

        assertNoErrors(pass1Diagnostics);

        // --- Pass 2: compile client code that uses the generated builder chain ---
        // Include tag annotation sources so the processor discovers them in this round.
        // The tag annotation .class files from pass 1 are already on the classpath,
        // but including the sources ensures the processor's TagDiscoverer finds them.
        final String usageSource =
                "package testpkg;\n" +
                "public class GeneratedBuilderTagTest {\n" +
                "    void test() {\n" +
                "        @FirstName String name = \"John\";\n" +
                "        TaggedUser user = TaggedUser.constructor()\n" +
                "            .userId(10L)\n" +           // literal → strict tagged: no warning
                "            .firstName(name)\n" +        // @FirstName → @FirstName: no warning
                "            .lastName(name)\n" +          // @FirstName → @LastName: WARNING
                "            .construct();\n" +
                "    }\n" +
                "}\n";

        final List<Diagnostic<? extends JavaFileObject>> pass2Diagnostics = compileWithProcessor(
                List.of("testpkg.UserId", "testpkg.FirstName", "testpkg.LastName",
                        "testpkg.GeneratedBuilderTagTest"),
                List.of(USER_ID_SOURCE, FIRST_NAME_SOURCE, LAST_NAME_SOURCE,
                        usageSource),
                outputDir);

        assertNoErrors(pass2Diagnostics);
        assertAllWarningsNotErrors(pass2Diagnostics);

        final List<String> warnings = warningMessages(pass2Diagnostics);
        // @FirstName → @LastName parameter should produce tag mismatch
        assertTrue(warnings.stream().anyMatch(w -> w.contains("tag mismatch")),
                "Expected tag mismatch warning for @FirstName → @LastName in generated builder chain, got: "
                        + warnings);
    }

    // =========================================================================
    // Test 21 — Generated builder chain: no warning when all tags match
    // Validates: Requirements 8.1, 10.1, 10.2, 10.3
    //
    // Two-pass compilation (reuses pass 1 output from the same @TempDir):
    //   Pass 1: compile tag annotations + @Constructor record
    //   Pass 2: compile client code that passes correctly-tagged values
    //           to each generated stage method → no warnings
    // =========================================================================

    @Test
    void generatedBuilderChain_allTagsMatch_noWarning(
            @TempDir final Path outputDir) throws Exception {

        // --- Pass 1: compile tag annotations + @Constructor record ---
        final String taggedUserSource =
                "package testpkg;\n" +
                "import rawit.Constructor;\n" +
                "@Constructor\n" +
                "public record TaggedUser(\n" +
                "    @UserId long userId,\n" +
                "    @FirstName String firstName,\n" +
                "    @LastName String lastName\n" +
                ") { }\n";

        final List<Diagnostic<? extends JavaFileObject>> pass1Diagnostics = compileWithProcessor(
                List.of("testpkg.UserId", "testpkg.FirstName", "testpkg.LastName",
                        "testpkg.TaggedUser"),
                List.of(USER_ID_SOURCE, FIRST_NAME_SOURCE, LAST_NAME_SOURCE,
                        taggedUserSource),
                outputDir);

        assertNoErrors(pass1Diagnostics);

        // --- Pass 2: compile client code with correctly-tagged values ---
        // Include tag annotation sources so the processor discovers them in this round.
        final String usageSource =
                "package testpkg;\n" +
                "public class GeneratedBuilderMatchTest {\n" +
                "    void test() {\n" +
                "        @FirstName String first = \"John\";\n" +
                "        @LastName String last = \"Doe\";\n" +
                "        TaggedUser user = TaggedUser.constructor()\n" +
                "            .userId(10L)\n" +           // literal → strict tagged: no warning
                "            .firstName(first)\n" +       // @FirstName → @FirstName: no warning
                "            .lastName(last)\n" +          // @LastName → @LastName: no warning
                "            .construct();\n" +
                "    }\n" +
                "}\n";

        final List<Diagnostic<? extends JavaFileObject>> pass2Diagnostics = compileWithProcessor(
                List.of("testpkg.UserId", "testpkg.FirstName", "testpkg.LastName",
                        "testpkg.GeneratedBuilderMatchTest"),
                List.of(USER_ID_SOURCE, FIRST_NAME_SOURCE, LAST_NAME_SOURCE,
                        usageSource),
                outputDir);

        assertNoErrors(pass2Diagnostics);

        final List<String> warnings = warningMessages(pass2Diagnostics);
        assertTrue(warnings.stream().noneMatch(w ->
                        w.contains("tag mismatch") || w.contains("strict")),
                "Expected no warnings when all tags match in generated builder chain, got: "
                        + warnings);
    }
}
