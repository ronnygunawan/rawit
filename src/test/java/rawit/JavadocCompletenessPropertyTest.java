package rawit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that every public constructor and public non-void method in
 * {@code src/main/java/rawit/} has complete Javadoc, and that no broken
 * {@code {@link}} references appear in the source.
 *
 * <p><b>Validates: Requirements 1.2–1.13</b>
 *
 * <p><b>NOTE</b>: This test is expected to FAIL on unfixed code — failure confirms the bug exists.
 * It will pass once all 13 affected files have been fixed.
 */
class JavadocCompletenessPropertyTest {

    private static final Path MAIN_ROOT = Paths.get("src/main/java");

    private static final Pattern JAVADOC_BLOCK = Pattern.compile(
            "/\\*\\*[\\s\\S]*?\\*/", Pattern.MULTILINE);

    private static final Pattern PUBLIC_CONSTRUCTOR = Pattern.compile(
            "(?m)^\\s*public\\s+(\\w+)\\s*(?:\\([^)]*\\)|\\{)");

    private static final Pattern PUBLIC_NON_VOID_METHOD = Pattern.compile(
            "(?m)^\\s*public\\s+(?:(?:static|final|synchronized|abstract|default)\\s+)*" +
            "(?!void\\b)(\\S+)\\s+(\\w+)\\s*\\(");

    private static final String BROKEN_LINK = "{@link javax.tools.Filer#getResource}";

    /**
     * Property 1: Bug Condition — Missing Javadoc in Main Sources.
     *
     * <p>For every {@code .java} file under {@code src/main/java/rawit/}:
     * <ol>
     *   <li>Every public constructor must have a preceding Javadoc comment.</li>
     *   <li>Every public non-void method with Javadoc must have a {@code @return} tag.</li>
     *   <li>No {@code {@link javax.tools.Filer#getResource}} may appear (broken link).</li>
     * </ol>
     */
    @Test
    void property1_javadocCompleteness() throws IOException {
        final List<String> failures = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(MAIN_ROOT)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                  .filter(Files::isRegularFile)
                  .forEach(javaFile -> {
                      try {
                          checkFile(javaFile, failures);
                      } catch (IOException e) {
                          failures.add("Could not read " + javaFile + ": " + e.getMessage());
                      }
                  });
        }

        assertTrue(failures.isEmpty(),
                "Javadoc completeness violations:\n" + String.join("\n", failures));
    }

    private static void checkFile(final Path javaFile, final List<String> failures)
            throws IOException {
        final String source = Files.readString(javaFile);
        final String fileName = javaFile.getFileName().toString();
        final List<int[]> javadocRanges = findJavadocRanges(source);

        if (source.contains(BROKEN_LINK)) {
            failures.add(fileName + ": contains broken {@link}: " + BROKEN_LINK);
        }
        collectConstructorFailures(source, fileName, javadocRanges, failures);
        collectReturnTagFailures(source, fileName, javadocRanges, failures);
    }

    private static List<int[]> findJavadocRanges(final String source) {
        final List<int[]> ranges = new ArrayList<>();
        final Matcher m = JAVADOC_BLOCK.matcher(source);
        while (m.find()) ranges.add(new int[]{m.start(), m.end()});
        return ranges;
    }

    /**
     * Returns the Javadoc block immediately preceding {@code memberStart}, or {@code null}.
     * Only whitespace, annotation lines, and {@code *}-continuation lines are allowed between
     * the Javadoc end and the member declaration.
     *
     * @param source        full source text
     * @param memberStart   character position of the member declaration
     * @param javadocRanges pre-computed list of [start, end] ranges for all Javadoc blocks
     * @return the Javadoc text, or {@code null} if none found
     */
    private static String findPrecedingJavadoc(
            final String source,
            final int memberStart,
            final List<int[]> javadocRanges
    ) {
        int[] best = null;
        for (final int[] range : javadocRanges) {
            if (range[1] <= memberStart && (best == null || range[1] > best[1])) {
                best = range;
            }
        }
        if (best == null) return null;

        final String between = source.substring(best[1], memberStart);
        for (final String line : between.split("\n", -1)) {
            final String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("@") && !t.startsWith("*")) return null;
        }
        return source.substring(best[0], best[1]);
    }

    private static void collectConstructorFailures(
            final String source,
            final String fileName,
            final List<int[]> javadocRanges,
            final List<String> failures
    ) {
        final List<String> declaredTypes = findDeclaredTypeNames(source);
        final Matcher m = PUBLIC_CONSTRUCTOR.matcher(source);
        while (m.find()) {
            final String name = m.group(1);
            if (!declaredTypes.contains(name)) continue;
            if (findPrecedingJavadoc(source, m.start(), javadocRanges) == null) {
                failures.add(fileName + ": constructor '" + name + "' has no Javadoc");
            }
        }
    }

    private static void collectReturnTagFailures(
            final String source,
            final String fileName,
            final List<int[]> javadocRanges,
            final List<String> failures
    ) {
        final Matcher m = PUBLIC_NON_VOID_METHOD.matcher(source);
        while (m.find()) {
            final String returnType = m.group(1);
            final String methodName = m.group(2);
            if (isModifierKeyword(returnType)) continue;
            final String javadoc = findPrecedingJavadoc(source, m.start(), javadocRanges);
            if (javadoc != null && !javadoc.contains("@return")) {
                failures.add(fileName + ": method '" + methodName +
                        "' (returns " + returnType + ") has Javadoc but no @return tag");
            }
        }
    }

    private static List<String> findDeclaredTypeNames(final String source) {
        final List<String> names = new ArrayList<>();
        final Matcher m = Pattern.compile("(?:class|record|interface|enum)\\s+(\\w+)")
                                 .matcher(source);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    private static boolean isModifierKeyword(final String word) {
        return switch (word) {
            case "static", "final", "synchronized", "abstract", "default",
                 "native", "strictfp", "transient", "volatile",
                 "record", "class", "interface", "enum" -> true;
            default -> false;
        };
    }
}
