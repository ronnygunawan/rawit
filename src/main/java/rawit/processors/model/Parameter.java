package rawit.processors.model;

/**
 * Represents a single method parameter as a (name, JVM type descriptor) pair.
 *
 * <p>Primitive types use their JVM one-character descriptors ({@code I}, {@code J}, {@code F},
 * {@code D}, {@code Z}, {@code B}, {@code C}, {@code S}). Object types use
 * {@code L<binary-name>;} form. Arrays use the {@code [} prefix.
 *
 * @param name           the parameter name as it appears in source
 * @param typeDescriptor the JVM type descriptor, e.g. {@code "I"} or {@code "Ljava/lang/String;"}
 */
public record Parameter(String name, String typeDescriptor) {}
