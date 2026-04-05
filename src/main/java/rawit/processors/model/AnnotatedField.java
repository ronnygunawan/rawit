package rawit.processors.model;

/**
 * Immutable model of a validated field annotated with {@code @Getter}.
 *
 * <p>Captures everything needed for bytecode injection, with no dependency
 * on {@code javax.lang.model} types after construction.
 *
 * @param enclosingClassName   binary name of the enclosing class, e.g. {@code "com/example/Foo"}
 * @param fieldName            simple field name, e.g. {@code "firstName"}
 * @param fieldTypeDescriptor  JVM type descriptor, e.g. {@code "Ljava/lang/String;"}
 * @param fieldTypeSignature   generic signature (nullable), e.g. {@code "Ljava/util/List<Ljava/lang/String;>;"}
 * @param isStatic             {@code true} if the field is declared {@code static}
 * @param getterName           computed getter method name, e.g. {@code "getFirstName"}
 */
public record AnnotatedField(
        String enclosingClassName,
        String fieldName,
        String fieldTypeDescriptor,
        String fieldTypeSignature,
        boolean isStatic,
        String getterName
) {
    /** Compact constructor — validates non-null invariants. */
    public AnnotatedField {
        java.util.Objects.requireNonNull(enclosingClassName, "enclosingClassName");
        java.util.Objects.requireNonNull(fieldName, "fieldName");
        java.util.Objects.requireNonNull(fieldTypeDescriptor, "fieldTypeDescriptor");
        java.util.Objects.requireNonNull(getterName, "getterName");
        // fieldTypeSignature is intentionally nullable (non-generic fields)
    }
}
