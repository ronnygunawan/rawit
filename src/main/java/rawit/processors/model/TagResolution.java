package rawit.processors.model;

/**
 * Represents the result of resolving the effective tag on an element.
 *
 * <p>Either the element carries a recognized tag annotation ({@link Tagged})
 * or it does not ({@link Untagged}).
 */
public sealed interface TagResolution
        permits TagResolution.Tagged, TagResolution.Untagged {

    /**
     * The element carries a recognized tag annotation.
     *
     * @param tag metadata for the resolved tag annotation
     */
    record Tagged(TagInfo tag) implements TagResolution {
        /** Compact constructor — validates non-null invariants. */
        public Tagged {
            java.util.Objects.requireNonNull(tag, "tag");
        }
    }

    /** The element has no recognized tag annotation. */
    record Untagged() implements TagResolution {}
}
