package rg.rawit.processors.validation;

import rg.rawit.Constructor;
import rg.rawit.Invoker;

import javax.annotation.processing.Messager;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import java.util.List;

/**
 * Validates elements annotated with {@code @Invoker} or {@code @Constructor}.
 *
 * <p>All violated rules are emitted as {@link Diagnostic.Kind#ERROR} messages via the
 * supplied {@link Messager}. The validator checks ALL applicable rules (no short-circuit),
 * so that every error is surfaced in a single compilation pass.
 *
 * <p>Returns {@link ValidationResult#valid()} when no rules are violated, or
 * {@link ValidationResult#invalid()} when at least one error was emitted.
 */
public class ElementValidator {

    /**
     * Validates the given annotated element and emits diagnostics for every violated rule.
     *
     * @param element  the element carrying {@code @Invoker} or {@code @Constructor}
     * @param messager the compiler messager used to emit diagnostics
     * @return {@link ValidationResult#valid()} if all rules pass,
     *         {@link ValidationResult#invalid()} if any rule was violated
     */
    public ValidationResult validate(final Element element, final Messager messager) {
        final boolean hasInvoker = element.getAnnotation(Invoker.class) != null;
        final boolean hasConstructor = element.getAnnotation(Constructor.class) != null;

        if (hasInvoker) {
            return validateInvoker(element, messager);
        } else if (hasConstructor) {
            return validateConstructor(element, messager);
        }

        // No recognised annotation — nothing to validate
        return ValidationResult.valid();
    }

    // -------------------------------------------------------------------------
    // @Invoker validation
    // -------------------------------------------------------------------------

    private ValidationResult validateInvoker(final Element element, final Messager messager) {
        boolean hasError = false;

        // Requirement 1.1 / 2.1 — must be METHOD or CONSTRUCTOR
        final ElementKind kind = element.getKind();
        if (kind != ElementKind.METHOD && kind != ElementKind.CONSTRUCTOR) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@Invoker may only target methods or constructors",
                    element);
            // Cannot proceed with further checks if the element is not executable
            return ValidationResult.invalid();
        }

        final ExecutableElement exec = (ExecutableElement) element;

        // Requirement 1.1 / 2.1 — param count ≥ 1
        if (exec.getParameters().isEmpty()) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@Invoker requires at least one parameter",
                    element);
            hasError = true;
        }

        // Requirement 1.2 / 2.2 — not private
        if (exec.getModifiers().contains(Modifier.PRIVATE)) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@Invoker requires at least package-private visibility",
                    element);
            hasError = true;
        }

        // Requirement 3.7 / 13.1 — no existing zero-param overload with the same name
        if (hasConflictingOverload(exec)) {
            final String overloadName = resolvedInvokerOverloadName(exec);
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "a parameterless overload named '" + overloadName + "' already exists on this class",
                    element);
            hasError = true;
        }

        return hasError ? ValidationResult.invalid() : ValidationResult.valid();
    }

    // -------------------------------------------------------------------------
    // @Constructor validation
    // -------------------------------------------------------------------------

    private ValidationResult validateConstructor(final Element element, final Messager messager) {
        boolean hasError = false;

        // Requirement 15.1 — must be a CONSTRUCTOR
        if (element.getKind() != ElementKind.CONSTRUCTOR) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@Constructor may only target constructors",
                    element);
            // Cannot proceed with further checks if the element is not a constructor
            return ValidationResult.invalid();
        }

        final ExecutableElement exec = (ExecutableElement) element;

        // Requirement 15.2 — param count ≥ 1
        if (exec.getParameters().isEmpty()) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "staged construction requires at least one parameter",
                    element);
            hasError = true;
        }

        // Requirement 15.3 — not private
        if (exec.getModifiers().contains(Modifier.PRIVATE)) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@Constructor requires at least package-private visibility",
                    element);
            hasError = true;
        }

        // Conflict detection: check for existing zero-param method named "constructor"
        // (Requirement 3.7 applied to @Constructor — entry point is always "constructor()")
        if (hasConstructorEntryPointConflict(exec)) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "a parameterless overload named 'constructor' already exists on this class",
                    element);
            hasError = true;
        }

        return hasError ? ValidationResult.invalid() : ValidationResult.valid();
    }

    // -------------------------------------------------------------------------
    // Conflict detection helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when the enclosing type already declares a zero-parameter method
     * whose name matches the parameterless overload that would be injected for this
     * {@code @Invoker}-annotated element.
     *
     * <ul>
     *   <li>For a regular method {@code bar}, the overload name is {@code "bar"}.</li>
     *   <li>For a constructor on class {@code Foo}, the overload name is {@code "foo"}
     *       (class name lowercased — Requirement 3.6).</li>
     * </ul>
     */
    private boolean hasConflictingOverload(final ExecutableElement exec) {
        final String overloadName = resolvedInvokerOverloadName(exec);
        return enclosingTypeHasZeroParamMethod(exec, overloadName);
    }

    /**
     * Returns {@code true} when the enclosing type already declares a zero-parameter method
     * named {@code "constructor"} (the fixed entry-point name for {@code @Constructor}).
     */
    private boolean hasConstructorEntryPointConflict(final ExecutableElement exec) {
        return enclosingTypeHasZeroParamMethod(exec, "constructor");
    }

    /**
     * Scans the enclosed elements of the enclosing {@link TypeElement} for a method with the
     * given name and zero parameters.
     */
    private boolean enclosingTypeHasZeroParamMethod(final ExecutableElement exec,
                                                     final String methodName) {
        final Element enclosing = exec.getEnclosingElement();
        if (!(enclosing instanceof TypeElement typeElement)) {
            return false;
        }

        for (final Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            final ExecutableElement candidate = (ExecutableElement) enclosed;
            if (candidate.getSimpleName().contentEquals(methodName)
                    && candidate.getParameters().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the name of the parameterless overload that would be injected for a
     * {@code @Invoker}-annotated element.
     *
     * <ul>
     *   <li>Regular method → the method's own simple name.</li>
     *   <li>Constructor → the enclosing class name lowercased (Requirement 3.6).</li>
     * </ul>
     */
    private String resolvedInvokerOverloadName(final ExecutableElement exec) {
        if (exec.getKind() == ElementKind.CONSTRUCTOR) {
            final Element enclosing = exec.getEnclosingElement();
            return enclosing.getSimpleName().toString().toLowerCase();
        }
        return exec.getSimpleName().toString();
    }
}
