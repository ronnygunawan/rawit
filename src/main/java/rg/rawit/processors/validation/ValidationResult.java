package rg.rawit.processors.validation;

/**
 * Sealed result type returned by {@link ElementValidator}.
 * Either {@link Valid} (no errors) or {@link Invalid} (one or more errors were emitted).
 */
public sealed interface ValidationResult permits ValidationResult.Valid, ValidationResult.Invalid {

    /** The annotated element passed all validation rules. */
    record Valid() implements ValidationResult {}

    /**
     * One or more validation rules were violated.
     * The errors have already been emitted to the {@code Messager}; this record
     * simply signals that code generation should be skipped for this element.
     */
    record Invalid() implements ValidationResult {}

    static ValidationResult valid() {
        return new Valid();
    }

    static ValidationResult invalid() {
        return new Invalid();
    }
}
