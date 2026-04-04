package rawit;

import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Preservation test: verifies that the four pure in-memory property test classes
 * still have {@code tries = 100} on all their {@code @Property} methods.
 *
 * <p>This is a plain JUnit 5 test that inspects annotations via reflection.
 * It is NOT a jqwik property test itself.
 *
 * <p>Validates: Requirements 3.1, 3.2, 3.3, 3.4
 */
class SlowTestsPreservationTest {

    private static final int EXPECTED_TRIES = 100;

    /** Returns all methods annotated with {@code @Property} in the given class. */
    private List<Method> propertyMethods(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Property.class))
                .toList();
    }

    /** Asserts that every @Property method in the class has tries == expectedTries. */
    private void assertAllTriesEquals(Class<?> clazz, int expectedTries, int expectedCount) {
        List<Method> methods = propertyMethods(clazz);

        assertEquals(expectedCount, methods.size(),
                clazz.getSimpleName() + " must have exactly " + expectedCount + " @Property methods");

        for (Method m : methods) {
            int actual = m.getAnnotation(Property.class).tries();
            assertEquals(expectedTries, actual,
                    clazz.getSimpleName() + "." + m.getName()
                            + " must have tries = " + expectedTries + " but was " + actual);
        }
    }

    @Test
    void mergeTreeBuilderPropertyTest_hasTriesOf100() throws ClassNotFoundException {
        // MergeTreeBuilderPropertyTest: property21, property22, property23 (x2), property24 = 5 methods
        Class<?> clazz = Class.forName("rawit.processors.merge.MergeTreeBuilderPropertyTest");
        assertAllTriesEquals(clazz, EXPECTED_TRIES, 5);
    }

    @Test
    void invokerClassSpecPropertyTest_hasTriesOf100() throws ClassNotFoundException {
        // InvokerClassSpecPropertyTest: property2, property5, property6 (x2), property7 (x2), property8 (x2) = 8 methods
        Class<?> clazz = Class.forName("rawit.processors.codegen.InvokerClassSpecPropertyTest");
        assertAllTriesEquals(clazz, EXPECTED_TRIES, 8);
    }

    @Test
    void stageInterfaceSpecPropertyTest_hasTriesOf100() throws ClassNotFoundException {
        // StageInterfaceSpecPropertyTest: property9, property10, property11, property13,
        // stageInterfaceNamesFollowConvention, property3, primitiveTypesAreNotBoxed = 7 methods
        Class<?> clazz = Class.forName("rawit.processors.codegen.StageInterfaceSpecPropertyTest");
        assertAllTriesEquals(clazz, EXPECTED_TRIES, 7);
    }

    @Test
    void terminalInterfaceSpecPropertyTest_hasTriesOf100() throws ClassNotFoundException {
        // TerminalInterfaceSpecPropertyTest: property12 (x2), property13 (x2), property14 (x2),
        // invokeReturnTypeMatchesDescriptor, property4, property5 = 9 methods
        Class<?> clazz = Class.forName("rawit.processors.codegen.TerminalInterfaceSpecPropertyTest");
        assertAllTriesEquals(clazz, EXPECTED_TRIES, 9);
    }
}
