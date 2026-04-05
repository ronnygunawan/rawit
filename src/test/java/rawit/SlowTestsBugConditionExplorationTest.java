package rawit;

import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Bug condition exploration test for the slow-tests-fix bugfix spec.
 *
 * <p>Validates: Requirements 2.1, 2.2, 2.3, 2.4
 *
 * <p>This test asserts the EXPECTED (fixed) values — it will FAIL on unfixed code,
 * confirming the bug exists. When the fix is applied, this test will pass.
 *
 * <p>Uses reflection to read the {@code @Property(tries = N)} annotation on every
 * {@code @Property}-annotated method in the four affected classes.
 */
class SlowTestsBugConditionExplorationTest {

    private static final int EXPECTED_TRIES = 5;

    /**
     * Collects all methods annotated with {@code @Property} in the given class
     * and returns a list of violations where {@code tries != expectedTries}.
     */
    private List<String> findViolations(Class<?> testClass, int expectedTries) {
        List<String> violations = new ArrayList<>();
        for (Method method : testClass.getDeclaredMethods()) {
            Property annotation = method.getAnnotation(Property.class);
            if (annotation != null) {
                int actual = annotation.tries();
                if (actual != expectedTries) {
                    violations.add(testClass.getSimpleName() + "#" + method.getName()
                            + ": tries=" + actual + " (expected " + expectedTries + ")");
                }
            }
        }
        return violations;
    }

    /**
     * Asserts that every {@code @Property} method in the given class has exactly
     * {@code expectedTries} tries, and that the class has exactly {@code expectedCount}
     * property methods.
     */
    private void assertTriesAndCount(Class<?> testClass, int expectedTries, int expectedCount) {
        long propertyMethodCount = Arrays.stream(testClass.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Property.class))
                .count();

        assertEquals(expectedCount, propertyMethodCount,
                testClass.getSimpleName() + " must have exactly " + expectedCount
                        + " @Property methods, found " + propertyMethodCount);

        List<String> violations = findViolations(testClass, expectedTries);
        if (!violations.isEmpty()) {
            fail("Found @Property methods with tries != " + expectedTries
                    + " in " + testClass.getSimpleName() + ":\n  "
                    + String.join("\n  ", violations));
        }
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Could not load class: " + name, e);
        }
    }

    /**
     * Validates: Requirements 2.1
     *
     * <p>Asserts that all 12 property methods in {@code ElementValidatorPropertyTest}
     * have {@code tries = 5}. FAILS on unfixed code (currently tries=100).
     */
    @Test
    void elementValidatorPropertyTest_allPropertiesHaveTries5() {
        assertTriesAndCount(
                loadClass("rawit.processors.validation.ElementValidatorPropertyTest"),
                EXPECTED_TRIES, 12);
    }

    /**
     * Validates: Requirements 2.2
     *
     * <p>Asserts that all 8 property methods in {@code BytecodeInjectorPropertyTest}
     * have {@code tries = 5}. FAILS on unfixed code (currently tries=100).
     */
    @Test
    void bytecodeInjectorPropertyTest_allPropertiesHaveTries5() {
        assertTriesAndCount(
                loadClass("rawit.processors.inject.BytecodeInjectorPropertyTest"),
                EXPECTED_TRIES, 8);
    }

    /**
     * Validates: Requirements 2.3
     *
     * <p>Asserts that all 3 property methods in {@code RawitAnnotationProcessorPropertyTest}
     * have {@code tries = 5}. FAILS on unfixed code (currently tries=10).
     */
    @Test
    void rawitAnnotationProcessorPropertyTest_allPropertiesHaveTries5() {
        assertTriesAndCount(
                loadClass("rawit.processors.RawitAnnotationProcessorPropertyTest"),
                EXPECTED_TRIES, 3);
    }

    /**
     * Validates: Requirements 2.4
     *
     * <p>Asserts that all 9 property methods in {@code RawitAnnotationProcessorConstructorPropertyTest}
     * have {@code tries = 5}. FAILS on unfixed code (currently tries=10).
     */
    @Test
    void rawitAnnotationProcessorConstructorPropertyTest_allPropertiesHaveTries5() {
        assertTriesAndCount(
                loadClass("rawit.processors.RawitAnnotationProcessorConstructorPropertyTest"),
                EXPECTED_TRIES, 9);
    }
}
