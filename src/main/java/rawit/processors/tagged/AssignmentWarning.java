package rawit.processors.tagged;

import rawit.processors.model.TagInfo;

/**
 * Sealed hierarchy representing the three kinds of warnings the tagged value
 * analyzer can emit for assignment-like expressions.
 *
 * <p>Each variant carries the {@link TagInfo} needed to produce a clear
 * diagnostic message via {@link #toMessage()}.
 */
public sealed interface AssignmentWarning
        permits AssignmentWarning.TagMismatch,
                AssignmentWarning.StrictTaggedToUntagged,
                AssignmentWarning.StrictUntaggedToTagged {

    /**
     * Source and target carry different tag annotations.
     *
     * @param sourceTag the tag on the source (RHS) expression
     * @param targetTag the tag on the target (LHS) element
     */
    record TagMismatch(TagInfo sourceTag, TagInfo targetTag) implements AssignmentWarning {
        /** Compact constructor — validates non-null invariants. */
        public TagMismatch {
            java.util.Objects.requireNonNull(sourceTag, "sourceTag");
            java.util.Objects.requireNonNull(targetTag, "targetTag");
        }

        @Override
        public String toMessage() {
            return "tag mismatch: assigning @" + simpleName(sourceTag)
                    + " value to @" + simpleName(targetTag) + " target";
        }
    }

    /**
     * A strict-tagged value is assigned to an untagged target.
     *
     * @param tag the strict tag on the source expression
     */
    record StrictTaggedToUntagged(TagInfo tag) implements AssignmentWarning {
        /** Compact constructor — validates non-null invariants. */
        public StrictTaggedToUntagged {
            java.util.Objects.requireNonNull(tag, "tag");
        }

        @Override
        public String toMessage() {
            return "assigning @" + simpleName(tag) + " (strict) value to untagged target";
        }
    }

    /**
     * An untagged value is assigned to a strict-tagged target.
     *
     * @param tag the strict tag on the target element
     */
    record StrictUntaggedToTagged(TagInfo tag) implements AssignmentWarning {
        /** Compact constructor — validates non-null invariants. */
        public StrictUntaggedToTagged {
            java.util.Objects.requireNonNull(tag, "tag");
        }

        @Override
        public String toMessage() {
            return "assigning untagged value to @" + simpleName(tag) + " (strict) target";
        }
    }

    /** Formats this warning as a human-readable diagnostic message. */
    String toMessage();

    /**
     * Extracts the simple name from a {@link TagInfo}'s fully qualified annotation name.
     * For example, {@code "com.example.UserId"} becomes {@code "UserId"}.
     */
    private static String simpleName(TagInfo tag) {
        final String fqn = tag.annotationFqn();
        final int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
