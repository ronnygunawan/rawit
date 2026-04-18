package rawit.processors.model;

/**
 * Lightweight model capturing the metadata for a single tag annotation.
 *
 * <p>Built during the discovery phase when the processor scans for annotation
 * types annotated with {@code @TaggedValue}, and stored in the tag map passed
 * to the analyzer.
 *
 * @param annotationFqn fully qualified name of the tag annotation, e.g. {@code "com.example.UserId"}
 * @param strict        {@code true} for strict mode, {@code false} for lax mode
 */
public record TagInfo(
        String annotationFqn,
        boolean strict
) {
    /** Compact constructor — validates non-null invariants. */
    public TagInfo {
        java.util.Objects.requireNonNull(annotationFqn, "annotationFqn");
    }
}
