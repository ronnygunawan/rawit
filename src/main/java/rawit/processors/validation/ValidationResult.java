package rawit.processors.validation;

/**
 * Sealed result type returned by {@link ElementValidator}.
 * Either {@link Valid} (no errors) or {@link Invalid} (one or more errors were emitted).
 */
public sealed interface ValidationResult permits ValidationResult.Valid, ValidationResult.Invalid {

    /** The annotated element passed all validation rules. */
    record Valid() implements ValidationResult {
        /** Creates a {@link Valid} result. */
        public Valid {}
    }

    /**
     * One or more validation rules were violated.
     * The errors have already been emitted to the {@code Messager}; this record
     * simply signals that code generation should be skipped for this element.
     */
    record Invalid() implements ValidationResult {
        /** Creates an {@link Invalid} result. */
        public Invalid {}
    }

    /**
     * Returns a {@link Valid} result.
     *
     * @return a new {@link Valid} instance
     */
    static ValidationResult valid() {
        return new Valid();
    }

    /**
     * Returns an {@link Invalid} result.
     *
     * @return a new {@link Invalid} instance
     */
    static ValidationResult invalid() {
        return new Invalid();
    }
}
