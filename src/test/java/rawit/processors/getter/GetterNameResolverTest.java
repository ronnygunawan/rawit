package rawit.processors.getter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GetterNameResolver}.
 *
 * <p>Tests specific examples from the getter naming decision table covering
 * primitive boolean, boxed Boolean, and standard type naming conventions.
 */
class GetterNameResolverTest {

    private final GetterNameResolver resolver = new GetterNameResolver();

    // -------------------------------------------------------------------------
    // Primitive boolean (descriptor "Z") — Requirement 4.1
    // -------------------------------------------------------------------------

    @Test
    void primitiveBooleanWithoutIsPrefix_returnsIsCapitalized() {
        // active → isActive
        assertEquals("isActive", resolver.resolve("active", "Z"));
    }

    // -------------------------------------------------------------------------
    // Primitive boolean with "is" + uppercase — Requirement 4.2
    // -------------------------------------------------------------------------

    @Test
    void primitiveBooleanWithIsUpperPrefix_returnsFieldNameAsIs() {
        // isActive → isActive
        assertEquals("isActive", resolver.resolve("isActive", "Z"));
    }

    // -------------------------------------------------------------------------
    // Primitive boolean with "is" + non-uppercase — Requirement 4.3
    // -------------------------------------------------------------------------

    @Test
    void primitiveBooleanWithIsNonUpperPrefix_returnsIsCapitalized() {
        // isinTimezone → isIsinTimezone
        assertEquals("isIsinTimezone", resolver.resolve("isinTimezone", "Z"));
    }

    // -------------------------------------------------------------------------
    // Boxed Boolean — Requirement 5.1
    // -------------------------------------------------------------------------

    @Test
    void boxedBooleanField_returnsGetCapitalized() {
        // Boolean active → getActive
        assertEquals("getActive", resolver.resolve("active", "Ljava/lang/Boolean;"));
    }

    // -------------------------------------------------------------------------
    // Standard type — Requirement 2.2
    // -------------------------------------------------------------------------

    @Test
    void stringField_returnsGetCapitalized() {
        // String name → getName
        assertEquals("getName", resolver.resolve("name", "Ljava/lang/String;"));
    }
}
