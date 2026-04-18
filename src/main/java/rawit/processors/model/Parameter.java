package rawit.processors.model;

import java.util.List;

/**
 * Represents a single method parameter as a (name, JVM type descriptor) pair,
 * optionally carrying fully qualified names of tag annotations.
 *
 * <p>Primitive types use their JVM one-character descriptors ({@code I}, {@code J}, {@code F},
 * {@code D}, {@code Z}, {@code B}, {@code C}, {@code S}). Object types use
 * {@code L<binary-name>;} form. Arrays use the {@code [} prefix.
 *
 * @param name             the parameter name as it appears in source
 * @param typeDescriptor   the JVM type descriptor, e.g. {@code "I"} or {@code "Ljava/lang/String;"}
 * @param annotationFqns   fully qualified names of tag annotations on this parameter (empty if none)
 */
public record Parameter(String name, String typeDescriptor, List<String> annotationFqns) {

    /**
     * Compact constructor that defaults {@code annotationFqns} to an empty list if null,
     * and defensively copies the list to ensure immutability.
     */
    public Parameter {
        annotationFqns = annotationFqns == null ? List.of() : List.copyOf(annotationFqns);
    }

    /**
     * Convenience constructor preserving backward compatibility with existing call sites.
     *
     * @param name           the parameter name
     * @param typeDescriptor the JVM type descriptor
     */
    public Parameter(String name, String typeDescriptor) {
        this(name, typeDescriptor, List.of());
    }
}
