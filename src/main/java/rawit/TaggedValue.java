package rawit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation that marks a custom annotation as a value type tag.
 * Apply this to your own annotation declarations to create tag annotations
 * (e.g., {@code @UserId}, {@code @FirstName}) that enable compile-time
 * value type safety without wrapper types.
 *
 * <p>The rawit annotation processor inspects assignments involving tagged
 * elements and emits compiler warnings for unsafe operations such as
 * tag mismatches and strict-mode violations.</p>
 *
 * <h2>Example</h2>
 * <pre>
 * &#64;TaggedValue(strict = true)
 * public &#64;interface UserId { }
 *
 * &#64;TaggedValue               // strict defaults to false (lax mode)
 * public &#64;interface FirstName { }
 * </pre>
 *
 * @see <a href="https://github.com/rawit">rawit documentation</a>
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface TaggedValue {

    /**
     * Controls whether assignments between tagged and untagged values produce warnings.
     *
     * <p>When {@code true} (strict mode), the analyzer warns on:</p>
     * <ul>
     *   <li>Assigning an untagged non-literal value to a strict-tagged target</li>
     *   <li>Assigning a strict-tagged value to an untagged target</li>
     * </ul>
     *
     * <p>When {@code false} (lax mode, the default), only tag mismatch assignments
     * produce warnings.</p>
     *
     * @return {@code true} for strict mode, {@code false} for lax mode
     */
    boolean strict() default false;
}
