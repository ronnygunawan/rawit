package rg.rawit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a constructor for automatic currying during compilation.
 * Injects a {@code public static constructor()} entry point and a staged construction chain
 * into the enclosing class. The chain ends with {@code .construct()}.
 */
@Target(ElementType.CONSTRUCTOR)
@Retention(RetentionPolicy.SOURCE)
public @interface Constructor { }
