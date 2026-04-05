package rawit.processors.getter;

import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for {@link GetterNameResolver}.
 *
 * <p>Each property runs a minimum of 100 iterations via jqwik.
 */
class GetterNameResolverPropertyTest {

    private final GetterNameResolver resolver = new GetterNameResolver();

    // -------------------------------------------------------------------------
    // Arbitraries
    // -------------------------------------------------------------------------

    /** JVM type descriptors excluding primitive boolean "Z". */
    private static final java.util.List<String> NON_BOOLEAN_DESCRIPTORS = java.util.List.of(
            "I", "J", "D", "F", "B", "C", "S",
            "Ljava/lang/String;", "Ljava/lang/Boolean;", "Ljava/lang/Object;",
            "[I", "[Ljava/lang/String;"
    );

    /**
     * Generates valid Java field names that do NOT start with "is" followed by an uppercase letter.
     * These are the "normal" primitive boolean fields that should get the "is" + capitalize treatment.
     */
    @Provide
    Arbitrary<String> fieldNameWithoutIsUpperPrefix() {
        return Arbitraries.of(
                "active", "enabled", "visible", "done", "flag", "ready",
                "isinTimezone", "island", "isolate", "isbn", "is", "i",
                "x", "value", "checked", "open"
        );
    }

    /**
     * Generates valid Java field names that start with "is" followed by an uppercase letter.
     * These should be returned as-is for primitive boolean.
     */
    @Provide
    Arbitrary<String> fieldNameWithIsUpperPrefix() {
        return Arbitraries.of(
                "isActive", "isEnabled", "isVisible", "isDone", "isReady",
                "isOpen", "isChecked", "isValid", "isEmpty", "isRunning"
        );
    }

    /**
     * Generates any valid Java field name for non-boolean type testing.
     */
    @Provide
    Arbitrary<String> anyFieldName() {
        return Arbitraries.of(
                "name", "firstName", "active", "isActive", "isinTimezone",
                "count", "value", "island", "isbn", "x", "enabled",
                "isEnabled", "data", "items", "flag"
        );
    }

    /**
     * Generates non-boolean JVM type descriptors (including boxed Boolean).
     */
    @Provide
    Arbitrary<String> nonBooleanDescriptor() {
        return Arbitraries.of(NON_BOOLEAN_DESCRIPTORS);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String capitalize(String name) {
        if (name.isEmpty()) return name;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static boolean startsWithIsUppercase(String name) {
        return name.length() > 2
                && name.startsWith("is")
                && Character.isUpperCase(name.charAt(2));
    }

    // -------------------------------------------------------------------------
    // Property 1: Getter name computation follows naming conventions
    // Feature: getter-annotation, Property 1: Getter name computation follows naming conventions
    // -------------------------------------------------------------------------

    /**
     * Primitive boolean without "is"+uppercase → "is" + capitalize(fieldName).
     *
     * <p><b>Validates: Requirements 2.2, 4.1, 4.3, 5.1</b>
     */
    @Property(tries = 100)
    void primitiveBooleanWithoutIsUpperPrefix_returnsIsCapitalized(
            @ForAll("fieldNameWithoutIsUpperPrefix") String fieldName
    ) {
        String result = resolver.resolve(fieldName, "Z");

        assertEquals("is" + capitalize(fieldName), result,
                "Primitive boolean field '" + fieldName + "' without is+uppercase prefix "
                        + "should produce 'is' + capitalize(fieldName)");
    }

    /**
     * Primitive boolean with "is"+uppercase → field name as-is.
     *
     * <p><b>Validates: Requirements 2.2, 4.2</b>
     */
    @Property(tries = 100)
    void primitiveBooleanWithIsUpperPrefix_returnsFieldNameAsIs(
            @ForAll("fieldNameWithIsUpperPrefix") String fieldName
    ) {
        String result = resolver.resolve(fieldName, "Z");

        assertEquals(fieldName, result,
                "Primitive boolean field '" + fieldName + "' starting with is+uppercase "
                        + "should be returned as-is");
    }

    /**
     * Non-primitive-boolean → "get" + capitalize(fieldName).
     * This includes boxed Boolean, all primitives except boolean, and all reference types.
     *
     * <p><b>Validates: Requirements 2.2, 5.1</b>
     */
    @Property(tries = 100)
    void nonPrimitiveBoolean_returnsGetCapitalized(
            @ForAll("anyFieldName") String fieldName,
            @ForAll("nonBooleanDescriptor") String descriptor
    ) {
        String result = resolver.resolve(fieldName, descriptor);

        assertEquals("get" + capitalize(fieldName), result,
                "Non-primitive-boolean field '" + fieldName + "' with descriptor '" + descriptor
                        + "' should produce 'get' + capitalize(fieldName)");
    }
}
