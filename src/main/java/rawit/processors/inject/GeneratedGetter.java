package rawit.processors.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CLASS-retained marker annotation added to getter methods injected by
 * {@link GetterBytecodeInjector}. Used by {@link rawit.processors.getter.GetterCollisionDetector}
 * to distinguish @Getter-generated methods from manually declared ones when the superclass
 * is already compiled (where {@code @Getter} has SOURCE retention and is stripped).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface GeneratedGetter {}
