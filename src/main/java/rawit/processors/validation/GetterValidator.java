package rawit.processors.validation;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * Validates elements annotated with {@code @Getter}.
 *
 * <p>All violated rules are emitted as {@link Diagnostic.Kind#ERROR} messages via the
 * supplied {@link Messager}. The validator checks ALL applicable rules (no short-circuit),
 * so that every error is surfaced in a single compilation pass.
 *
 * <p>Returns {@link ValidationResult#valid()} when no rules are violated, or
 * {@link ValidationResult#invalid()} when at least one error was emitted.
 */
public class GetterValidator {

    /** Creates a new {@code GetterValidator}. */
    public GetterValidator() {}

    /**
     * Validates the given {@code @Getter}-annotated field element and emits diagnostics
     * for every violated rule.
     *
     * @param element  the field element carrying {@code @Getter}
     * @param messager the compiler messager used to emit diagnostics
     * @return {@link ValidationResult#valid()} if all rules pass,
     *         {@link ValidationResult#invalid()} if any rule was violated
     */
    public ValidationResult validate(final Element element, final Messager messager) {
        boolean hasError = false;

        // Requirement 10.1 — reject volatile fields
        if (element.getModifiers().contains(Modifier.VOLATILE)) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@Getter is not supported on volatile fields",
                    element);
            hasError = true;
        }

        // Requirement 10.2 — reject transient fields
        if (element.getModifiers().contains(Modifier.TRANSIENT)) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@Getter is not supported on transient fields",
                    element);
            hasError = true;
        }

        // Requirement 11.1 — reject fields inside anonymous classes
        final Element enclosing = element.getEnclosingElement();
        if (enclosing instanceof TypeElement typeElement
                && typeElement.getNestingKind() == NestingKind.ANONYMOUS) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@Getter is not supported inside anonymous classes",
                    element);
            hasError = true;
        }

        return hasError ? ValidationResult.invalid() : ValidationResult.valid();
    }
}
