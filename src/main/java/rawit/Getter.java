package rawit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field for automatic getter method generation during compilation.
 * The generated getter is injected as a public method on the enclosing class.
 * Primitive {@code boolean} fields use the {@code is} prefix (legacy Lombok convention),
 * while all other types use the standard {@code get} prefix.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.SOURCE)
public @interface Getter { }
