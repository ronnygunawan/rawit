package rawit.processors.tagged;

import rawit.processors.model.TagInfo;
import rawit.processors.model.TagResolution;

import java.util.Optional;

/**
 * Pure-function component implementing the core warning decision logic for
 * tagged value assignments.
 *
 * <p>Given the resolved tags of a source (RHS) and target (LHS) expression,
 * plus a flag indicating whether the source is a literal or compile-time
 * constant, this class determines whether a warning should be emitted.
 *
 * <p>This class has no dependency on the Tree API or {@code javax.lang.model};
 * it operates solely on the {@link TagResolution} and {@link TagInfo} model types.
 *
 * @see AssignmentWarning
 * @see TagResolution
 */
public final class AssignmentChecker {

    /**
     * Determines whether a warning should be emitted for an assignment
     * from a source tag resolution to a target tag resolution.
     *
     * <p>The decision matrix:
     * <ul>
     *   <li>Untagged → Untagged: no warning</li>
     *   <li>Tagged(A) → Tagged(A) (same tag): no warning</li>
     *   <li>Tagged(A) → Tagged(B) (different tags): {@link AssignmentWarning.TagMismatch}</li>
     *   <li>Untagged → Tagged(A, strict) + literal: no warning (exempt)</li>
     *   <li>Untagged → Tagged(A, strict) + non-literal: {@link AssignmentWarning.StrictUntaggedToTagged}</li>
     *   <li>Untagged → Tagged(A, lax): no warning</li>
     *   <li>Tagged(A, strict) → Untagged: {@link AssignmentWarning.StrictTaggedToUntagged}</li>
     *   <li>Tagged(A, lax) → Untagged: no warning</li>
     * </ul>
     *
     * @param source           the tag resolution of the source (RHS)
     * @param target           the tag resolution of the target (LHS)
     * @param isLiteralOrConst whether the source expression is a literal or compile-time constant
     * @return the warning to emit, or empty if no warning
     */
    public Optional<AssignmentWarning> check(
            TagResolution source,
            TagResolution target,
            boolean isLiteralOrConst
    ) {
        if (source instanceof TagResolution.Untagged) {
            if (target instanceof TagResolution.Untagged) {
                // Untagged → Untagged: no warning
                return Optional.empty();
            }
            if (target instanceof TagResolution.Tagged tagged) {
                final TagInfo targetTag = tagged.tag();
                if (!targetTag.strict()) {
                    // Lax mode: no warning
                    return Optional.empty();
                }
                if (isLiteralOrConst) {
                    // Literals/constants are exempt from strict untagged→tagged warnings
                    return Optional.empty();
                }
                return Optional.of(new AssignmentWarning.StrictUntaggedToTagged(targetTag));
            }
        }
        if (source instanceof TagResolution.Tagged sourceTagged) {
            final TagInfo sourceTag = sourceTagged.tag();
            if (target instanceof TagResolution.Untagged) {
                if (sourceTag.strict()) {
                    return Optional.of(new AssignmentWarning.StrictTaggedToUntagged(sourceTag));
                }
                // Lax mode: no warning
                return Optional.empty();
            }
            if (target instanceof TagResolution.Tagged targetTagged) {
                final TagInfo targetTag = targetTagged.tag();
                if (sourceTag.annotationFqn().equals(targetTag.annotationFqn())) {
                    // Same tag: no warning
                    return Optional.empty();
                }
                // Different tags: always warn (regardless of strict/lax)
                return Optional.of(new AssignmentWarning.TagMismatch(sourceTag, targetTag));
            }
        }
        // Should not be reachable given the sealed interface, but satisfy the compiler
        return Optional.empty();
    }
}
