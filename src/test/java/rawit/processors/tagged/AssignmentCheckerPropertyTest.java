package rawit.processors.tagged;

import net.jqwik.api.*;
import rawit.processors.model.TagInfo;
import rawit.processors.model.TagResolution;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for {@link AssignmentChecker}.
 *
 * <p>Each property runs a minimum of 100 iterations via jqwik.
 */
class AssignmentCheckerPropertyTest {

    private final AssignmentChecker checker = new AssignmentChecker();

    // -------------------------------------------------------------------------
    // Arbitraries
    // -------------------------------------------------------------------------

    private static final String[] FQN_POOL = {
            "com.example.UserId", "com.example.FirstName", "com.example.LastName",
            "com.example.Email", "com.example.PhoneNumber", "org.acme.OrderId",
            "org.acme.ProductId", "io.test.Amount", "io.test.Currency"
    };

    @Provide
    Arbitrary<TagInfo> anyTagInfo() {
        return Combinators.combine(
                Arbitraries.of(FQN_POOL),
                Arbitraries.of(true, false)
        ).as(TagInfo::new);
    }

    @Provide
    Arbitrary<TagInfo> strictTagInfo() {
        return Arbitraries.of(FQN_POOL).map(fqn -> new TagInfo(fqn, true));
    }

    @Provide
    Arbitrary<TagInfo> laxTagInfo() {
        return Arbitraries.of(FQN_POOL).map(fqn -> new TagInfo(fqn, false));
    }

    // -------------------------------------------------------------------------
    // Property 3: Tag mismatch always produces a warning regardless of strict/lax mode
    // Feature: tagged-value-annotation, Property 3: Tag mismatch always produces a warning regardless of strict/lax mode
    // -------------------------------------------------------------------------

    /**
     * Validates: Requirements 7.1, 7.2
     */
    @Property(tries = 100)
    void property3_tagMismatchAlwaysWarns(
            @ForAll("anyTagInfo") TagInfo sourceTag,
            @ForAll("anyTagInfo") TagInfo targetTag,
            @ForAll boolean isLiteralOrConst
    ) {
        Assume.that(!sourceTag.annotationFqn().equals(targetTag.annotationFqn()));

        TagResolution source = new TagResolution.Tagged(sourceTag);
        TagResolution target = new TagResolution.Tagged(targetTag);

        Optional<AssignmentWarning> result = checker.check(source, target, isLiteralOrConst);

        assertTrue(result.isPresent(), "Tag mismatch must always produce a warning");
        assertInstanceOf(AssignmentWarning.TagMismatch.class, result.get(),
                "Tag mismatch must produce a TagMismatch warning");
        AssignmentWarning.TagMismatch mismatch = (AssignmentWarning.TagMismatch) result.get();
        assertEquals(sourceTag, mismatch.sourceTag());
        assertEquals(targetTag, mismatch.targetTag());
    }

    // -------------------------------------------------------------------------
    // Property 4: Same-tag and untagged-to-untagged assignments never produce warnings
    // Feature: tagged-value-annotation, Property 4: Same-tag and untagged-to-untagged assignments never produce warnings
    // -------------------------------------------------------------------------

    /**
     * Validates: Requirements 8.1, 9.1
     */
    @Property(tries = 100)
    void property4_sameTagNeverWarns(
            @ForAll("anyTagInfo") TagInfo tag,
            @ForAll boolean isLiteralOrConst
    ) {
        TagResolution source = new TagResolution.Tagged(tag);
        TagResolution target = new TagResolution.Tagged(tag);

        Optional<AssignmentWarning> result = checker.check(source, target, isLiteralOrConst);

        assertTrue(result.isEmpty(),
                "Same-tag assignment must never produce a warning");
    }

    /**
     * Validates: Requirements 9.1
     */
    @Property(tries = 100)
    void property4_untaggedToUntaggedNeverWarns(
            @ForAll boolean isLiteralOrConst
    ) {
        TagResolution source = new TagResolution.Untagged();
        TagResolution target = new TagResolution.Untagged();

        Optional<AssignmentWarning> result = checker.check(source, target, isLiteralOrConst);

        assertTrue(result.isEmpty(),
                "Untagged-to-untagged assignment must never produce a warning");
    }

    // -------------------------------------------------------------------------
    // Property 5: Strict mode warns on tagged-to-untagged and untagged-to-tagged (non-literal)
    // Feature: tagged-value-annotation, Property 5: Strict mode warns on tagged-to-untagged and untagged-to-tagged (non-literal)
    // -------------------------------------------------------------------------

    /**
     * Validates: Requirements 3.1, 5.1
     */
    @Property(tries = 100)
    void property5_strictTaggedToUntaggedWarns(
            @ForAll("strictTagInfo") TagInfo tag
    ) {
        TagResolution source = new TagResolution.Tagged(tag);
        TagResolution target = new TagResolution.Untagged();

        Optional<AssignmentWarning> result = checker.check(source, target, false);

        assertTrue(result.isPresent(),
                "Strict tagged-to-untagged must produce a warning");
        assertInstanceOf(AssignmentWarning.StrictTaggedToUntagged.class, result.get());
        assertEquals(tag, ((AssignmentWarning.StrictTaggedToUntagged) result.get()).tag());
    }

    /**
     * Validates: Requirements 3.1
     */
    @Property(tries = 100)
    void property5_strictUntaggedToTaggedNonLiteralWarns(
            @ForAll("strictTagInfo") TagInfo tag
    ) {
        TagResolution source = new TagResolution.Untagged();
        TagResolution target = new TagResolution.Tagged(tag);

        Optional<AssignmentWarning> result = checker.check(source, target, false);

        assertTrue(result.isPresent(),
                "Strict untagged-to-tagged (non-literal) must produce a warning");
        assertInstanceOf(AssignmentWarning.StrictUntaggedToTagged.class, result.get());
        assertEquals(tag, ((AssignmentWarning.StrictUntaggedToTagged) result.get()).tag());
    }

    // -------------------------------------------------------------------------
    // Property 6: Lax mode never warns on tagged-to-untagged or untagged-to-tagged
    // Feature: tagged-value-annotation, Property 6: Lax mode never warns on tagged-to-untagged or untagged-to-tagged
    // -------------------------------------------------------------------------

    /**
     * Validates: Requirements 4.1, 6.1
     */
    @Property(tries = 100)
    void property6_laxTaggedToUntaggedNeverWarns(
            @ForAll("laxTagInfo") TagInfo tag,
            @ForAll boolean isLiteralOrConst
    ) {
        TagResolution source = new TagResolution.Tagged(tag);
        TagResolution target = new TagResolution.Untagged();

        Optional<AssignmentWarning> result = checker.check(source, target, isLiteralOrConst);

        assertTrue(result.isEmpty(),
                "Lax tagged-to-untagged must never produce a warning");
    }

    /**
     * Validates: Requirements 4.1
     */
    @Property(tries = 100)
    void property6_laxUntaggedToTaggedNeverWarns(
            @ForAll("laxTagInfo") TagInfo tag,
            @ForAll boolean isLiteralOrConst
    ) {
        TagResolution source = new TagResolution.Untagged();
        TagResolution target = new TagResolution.Tagged(tag);

        Optional<AssignmentWarning> result = checker.check(source, target, isLiteralOrConst);

        assertTrue(result.isEmpty(),
                "Lax untagged-to-tagged must never produce a warning");
    }

    // -------------------------------------------------------------------------
    // Property 7: Literals and constants are exempt from strict untagged-to-tagged warnings
    // Feature: tagged-value-annotation, Property 7: Literals and constants are exempt from strict untagged-to-tagged warnings
    // -------------------------------------------------------------------------

    /**
     * Validates: Requirements 3.2
     */
    @Property(tries = 100)
    void property7_literalsExemptFromStrictUntaggedToTagged(
            @ForAll("strictTagInfo") TagInfo tag
    ) {
        TagResolution source = new TagResolution.Untagged();
        TagResolution target = new TagResolution.Tagged(tag);

        Optional<AssignmentWarning> result = checker.check(source, target, true);

        assertTrue(result.isEmpty(),
                "Literals/constants must be exempt from strict untagged-to-tagged warnings");
    }
}
