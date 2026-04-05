package rawit.processors.getter;

/**
 * Pure function that computes the getter method name from a field name and JVM type descriptor.
 *
 * <p>Naming rules follow legacy Lombok conventions:
 * <ul>
 *   <li>Primitive {@code boolean} ({@code "Z"}): {@code is} + capitalize, unless the name
 *       already starts with {@code is} followed by an uppercase letter (returned as-is).</li>
 *   <li>All other types (including boxed {@code Boolean}): {@code get} + capitalize.</li>
 * </ul>
 */
public final class GetterNameResolver {

    /**
     * Resolves the getter method name for the given field.
     *
     * @param fieldName           the field name, e.g. {@code "active"}, {@code "isActive"}
     * @param fieldTypeDescriptor JVM type descriptor, e.g. {@code "Z"} for primitive boolean,
     *                            {@code "Ljava/lang/Boolean;"} for boxed Boolean
     * @return the getter method name, e.g. {@code "isActive"}, {@code "getActive"}
     */
    public String resolve(String fieldName, String fieldTypeDescriptor) {
        if ("Z".equals(fieldTypeDescriptor)) {
            return resolvePrimitiveBoolean(fieldName);
        }
        return "get" + capitalize(fieldName);
    }

    private String resolvePrimitiveBoolean(String fieldName) {
        if (fieldName.length() > 2
                && fieldName.startsWith("is")
                && Character.isUpperCase(fieldName.charAt(2))) {
            return fieldName;
        }
        return "is" + capitalize(fieldName);
    }

    private static String capitalize(String name) {
        if (name.isEmpty()) {
            return name;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
