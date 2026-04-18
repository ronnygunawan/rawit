package rawit.processors.tagged;

import org.junit.jupiter.api.Test;
import rawit.processors.model.TagInfo;
import rawit.processors.model.TagResolution;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AssignmentChecker} covering specific examples from the
 * requirements code examples.
 *
 * <p>Requirements: 3.1, 3.2, 4.1, 5.1, 6.1, 7.1, 8.1, 9.1
 */
class AssignmentCheckerTest {

    private final AssignmentChecker checker = new AssignmentChecker();

    // Tag annotations matching the requirements examples
    private static final TagInfo USER_ID = new TagInfo("com.example.UserId", true);       // strict
    private static final TagInfo FIRST_NAME = new TagInfo("com.example.FirstName", false); // lax
    private static final TagInfo LAST_NAME = new TagInfo("com.example.LastName", false);   // lax

    /**
     * {@code @UserId long taggedId = 42} → no warning (literal exempt).
     * Validates: Requirement 3.2
     */
    @Test
    void literalToStrictTagged_noWarning() {
        TagResolution source = new TagResolution.Untagged();
        TagResolution target = new TagResolution.Tagged(USER_ID);

        Optional<AssignmentWarning> result = checker.check(source, target, true);

        assertTrue(result.isEmpty(),
                "Literal assigned to strict tagged should produce no warning");
    }

    /**
     * {@code @UserId long taggedId2 = rawId} → StrictUntaggedToTagged warning.
     * Validates: Requirement 3.1
     */
    @Test
    void untaggedToStrictTagged_warning() {
        TagResolution source = new TagResolution.Untagged();
        TagResolution target = new TagResolution.Tagged(USER_ID);

        Optional<AssignmentWarning> result = checker.check(source, target, false);

        assertTrue(result.isPresent(), "Untagged to strict tagged should produce a warning");
        assertInstanceOf(AssignmentWarning.StrictUntaggedToTagged.class, result.get());
        assertEquals(USER_ID, ((AssignmentWarning.StrictUntaggedToTagged) result.get()).tag());
    }

    /**
     * {@code @FirstName String taggedName = rawName} → no warning (lax mode).
     * Validates: Requirement 4.1
     */
    @Test
    void untaggedToLaxTagged_noWarning() {
        TagResolution source = new TagResolution.Untagged();
        TagResolution target = new TagResolution.Tagged(FIRST_NAME);

        Optional<AssignmentWarning> result = checker.check(source, target, false);

        assertTrue(result.isEmpty(),
                "Untagged to lax tagged should produce no warning");
    }

    /**
     * {@code @LastName String lastName = user.firstName()} → TagMismatch warning.
     * Validates: Requirement 7.1
     */
    @Test
    void tagMismatch_warning() {
        TagResolution source = new TagResolution.Tagged(FIRST_NAME);
        TagResolution target = new TagResolution.Tagged(LAST_NAME);

        Optional<AssignmentWarning> result = checker.check(source, target, false);

        assertTrue(result.isPresent(), "Tag mismatch should produce a warning");
        assertInstanceOf(AssignmentWarning.TagMismatch.class, result.get());
        AssignmentWarning.TagMismatch mismatch = (AssignmentWarning.TagMismatch) result.get();
        assertEquals(FIRST_NAME, mismatch.sourceTag());
        assertEquals(LAST_NAME, mismatch.targetTag());
    }

    /**
     * {@code @FirstName String name2 = name1} → no warning (same tag).
     * Validates: Requirement 8.1
     */
    @Test
    void sameTag_noWarning() {
        TagResolution source = new TagResolution.Tagged(FIRST_NAME);
        TagResolution target = new TagResolution.Tagged(FIRST_NAME);

        Optional<AssignmentWarning> result = checker.check(source, target, false);

        assertTrue(result.isEmpty(),
                "Same tag assignment should produce no warning");
    }

    /**
     * {@code String b = a} → no warning (untagged→untagged).
     * Validates: Requirement 9.1
     */
    @Test
    void untaggedToUntagged_noWarning() {
        TagResolution source = new TagResolution.Untagged();
        TagResolution target = new TagResolution.Untagged();

        Optional<AssignmentWarning> result = checker.check(source, target, false);

        assertTrue(result.isEmpty(),
                "Untagged to untagged should produce no warning");
    }

    /**
     * Strict tagged → untagged should produce StrictTaggedToUntagged warning.
     * Validates: Requirement 5.1
     */
    @Test
    void strictTaggedToUntagged_warning() {
        TagResolution source = new TagResolution.Tagged(USER_ID);
        TagResolution target = new TagResolution.Untagged();

        Optional<AssignmentWarning> result = checker.check(source, target, false);

        assertTrue(result.isPresent(), "Strict tagged to untagged should produce a warning");
        assertInstanceOf(AssignmentWarning.StrictTaggedToUntagged.class, result.get());
        assertEquals(USER_ID, ((AssignmentWarning.StrictTaggedToUntagged) result.get()).tag());
    }

    /**
     * Lax tagged → untagged should produce no warning.
     * Validates: Requirement 6.1
     */
    @Test
    void laxTaggedToUntagged_noWarning() {
        TagResolution source = new TagResolution.Tagged(FIRST_NAME);
        TagResolution target = new TagResolution.Untagged();

        Optional<AssignmentWarning> result = checker.check(source, target, false);

        assertTrue(result.isEmpty(),
                "Lax tagged to untagged should produce no warning");
    }
}
