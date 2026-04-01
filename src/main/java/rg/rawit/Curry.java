package rg.rawit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method or a constructor for automatic currying during compilation.
 * Currying transforms a function with multiple arguments into a sequence of functions,
 * each taking a single argument.
 */
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
@Retention(RetentionPolicy.SOURCE)
public @interface Curry { }
