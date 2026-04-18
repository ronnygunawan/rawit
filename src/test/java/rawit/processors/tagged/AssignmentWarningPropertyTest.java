package rawit.processors.tagged;

import net.jqwik.api.*;
import rawit.processors.model.TagInfo;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for {@link AssignmentWarning} message formatting.
 *
 * <p>Each property runs a minimum of 100 iterations via jqwik.
 */
class AssignmentWarningPropertyTest {

    // -------------------------------------------------------------------------
    // Arbitraries
    // -------------------------------------------------------------------------

    @Provide
    Arbitrary<String> tagFqn() {
        Arbitrary<String> packagePart = Arbitraries.of(
                "com.example", "org.acme", "io.test", "net.foo.bar", "dev.app"
        );
        Arbitrary<String> simpleName = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(20)
                .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1));
        return Combinators.combine(packagePart, simpleName).as((pkg, name) -> pkg + "." + name);
    }

    @Provide
    Arbitrary<TagInfo> anyTagInfo() {
        return Combinators.combine(
                tagFqn(),
                Arbitraries.of(true, false)
        ).as(TagInfo::new);
    }

    // -------------------------------------------------------------------------
    // Property 9: Warning messages contain relevant tag annotation names
    // Feature: tagged-value-annotation, Property 9: Warning messages contain relevant tag annotation names
    // -------------------------------------------------------------------------

    /**
     * Validates: Requirements 12.1
     *
     * TagMismatch.toMessage() must contain both the source and target annotation names.
     */
    @Property(tries = 100)
    void property9_tagMismatchMessageContainsBothAnnotationNames(
            @ForAll("anyTagInfo") TagInfo sourceTag,
            @ForAll("anyTagInfo") TagInfo targetTag
    ) {
        Assume.that(!sourceTag.annotationFqn().equals(targetTag.annotationFqn()));

        AssignmentWarning.TagMismatch warning = new AssignmentWarning.TagMismatch(sourceTag, targetTag);
        String message = warning.toMessage();

        String sourceSimple = simpleName(sourceTag.annotationFqn());
        String targetSimple = simpleName(targetTag.annotationFqn());

        assertTrue(message.contains(sourceSimple),
                "TagMismatch message must contain source annotation name '" + sourceSimple
                        + "', but was: " + message);
        assertTrue(message.contains(targetSimple),
                "TagMismatch message must contain target annotation name '" + targetSimple
                        + "', but was: " + message);
    }

    /**
     * Validates: Requirements 12.2
     *
     * StrictTaggedToUntagged.toMessage() must contain the tag annotation name.
     */
    @Property(tries = 100)
    void property9_strictTaggedToUntaggedMessageContainsAnnotationName(
            @ForAll("anyTagInfo") TagInfo tag
    ) {
        AssignmentWarning.StrictTaggedToUntagged warning = new AssignmentWarning.StrictTaggedToUntagged(tag);
        String message = warning.toMessage();

        String simpleName = simpleName(tag.annotationFqn());

        assertTrue(message.contains(simpleName),
                "StrictTaggedToUntagged message must contain annotation name '" + simpleName
                        + "', but was: " + message);
    }

    /**
     * Validates: Requirements 12.2
     *
     * StrictUntaggedToTagged.toMessage() must contain the tag annotation name.
     */
    @Property(tries = 100)
    void property9_strictUntaggedToTaggedMessageContainsAnnotationName(
            @ForAll("anyTagInfo") TagInfo tag
    ) {
        AssignmentWarning.StrictUntaggedToTagged warning = new AssignmentWarning.StrictUntaggedToTagged(tag);
        String message = warning.toMessage();

        String simpleName = simpleName(tag.annotationFqn());

        assertTrue(message.contains(simpleName),
                "StrictUntaggedToTagged message must contain annotation name '" + simpleName
                        + "', but was: " + message);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
