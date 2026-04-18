package rawit.processors.tagged;

import net.jqwik.api.*;
import rawit.TaggedValue;
import rawit.processors.model.TagInfo;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for {@link TagDiscoverer}.
 *
 * <p>Each property runs a minimum of 100 iterations via jqwik.
 *
 * <p>Since {@code TagDiscoverer.discover()} depends on {@link RoundEnvironment}
 * and {@link ProcessingEnvironment}, this test creates lightweight stubs using
 * Java's {@link Proxy} API to simulate annotation processing types.
 */
// Feature: tagged-value-annotation, Property 1: Tag discovery recognizes exactly @TaggedValue-annotated annotations
class TagDiscovererPropertyTest {

    private static final String TAGGED_VALUE_FQN = TaggedValue.class.getCanonicalName();

    private static final String[] FQN_POOL = {
            "com.example.UserId", "com.example.FirstName", "com.example.LastName",
            "com.example.Email", "com.example.PhoneNumber", "org.acme.OrderId",
            "org.acme.ProductId", "io.test.Amount", "io.test.Currency"
    };

    private final TagDiscoverer discoverer = new TagDiscoverer();

    // -------------------------------------------------------------------------
    // Model for a simulated annotation declaration
    // -------------------------------------------------------------------------

    /**
     * Represents a simulated annotation type that may or may not be annotated
     * with {@code @TaggedValue}.
     */
    private record SimulatedAnnotation(
            String fqn,
            boolean hasTaggedValue,
            boolean strict
    ) {}

    // -------------------------------------------------------------------------
    // Arbitraries
    // -------------------------------------------------------------------------

    @Provide
    Arbitrary<List<SimulatedAnnotation>> simulatedAnnotations() {
        Arbitrary<SimulatedAnnotation> single = Combinators.combine(
                Arbitraries.of(FQN_POOL),
                Arbitraries.of(true, false),
                Arbitraries.of(true, false)
        ).as(SimulatedAnnotation::new);

        return single.list().ofMinSize(0).ofMaxSize(8)
                .map(list -> list.stream()
                        .collect(Collectors.toMap(
                                SimulatedAnnotation::fqn,
                                a -> a,
                                (a, b) -> a))
                        .values().stream().toList());
    }

    // -------------------------------------------------------------------------
    // Property 1: Tag discovery recognizes exactly @TaggedValue-annotated annotations
    // Feature: tagged-value-annotation, Property 1: Tag discovery recognizes exactly @TaggedValue-annotated annotations
    // -------------------------------------------------------------------------

    /**
     * Validates: Requirements 1.4
     */
    @Property(tries = 100)
    void property1_discoveryRecognizesExactlyTaggedValueAnnotations(
            @ForAll("simulatedAnnotations") List<SimulatedAnnotation> annotations
    ) {
        // Separate into those with @TaggedValue and those without
        List<SimulatedAnnotation> taggedValueAnnotations = annotations.stream()
                .filter(SimulatedAnnotation::hasTaggedValue)
                .toList();

        // Build stubs: RoundEnvironment returns only the @TaggedValue-annotated elements
        Set<TypeElement> taggedElements = taggedValueAnnotations.stream()
                .map(a -> createTypeElementStub(a.fqn(), a.strict()))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        RoundEnvironment roundEnv = createRoundEnvironmentStub(taggedElements);
        ProcessingEnvironment processingEnv = createProcessingEnvironmentStub();

        // Execute discovery
        Map<String, TagInfo> result = discoverer.discover(roundEnv, processingEnv);

        // Verify: result contains exactly the @TaggedValue-annotated annotations
        Set<String> expectedFqns = taggedValueAnnotations.stream()
                .map(SimulatedAnnotation::fqn)
                .collect(Collectors.toSet());

        assertEquals(expectedFqns, result.keySet(),
                "Discovered tag map keys must match exactly the @TaggedValue-annotated FQNs");

        // Verify: each entry has the correct strict value
        for (SimulatedAnnotation ann : taggedValueAnnotations) {
            TagInfo info = result.get(ann.fqn());
            assertNotNull(info, "Tag info must exist for " + ann.fqn());
            assertEquals(ann.fqn(), info.annotationFqn(),
                    "annotationFqn must match");
            assertEquals(ann.strict(), info.strict(),
                    "strict value must match for " + ann.fqn());
        }

        // Verify: no extra entries
        assertEquals(taggedValueAnnotations.size(), result.size(),
                "Result size must match the number of @TaggedValue-annotated annotations");
    }

    // -------------------------------------------------------------------------
    // Stub factories
    // -------------------------------------------------------------------------


    /**
     * Creates a stub {@link RoundEnvironment} that returns the given elements
     * when {@code getElementsAnnotatedWith(TaggedValue.class)} is called,
     * and an empty set for {@code getRootElements()}.
     */
    @SuppressWarnings("unchecked")
    private static RoundEnvironment createRoundEnvironmentStub(Set<TypeElement> elements) {
        return (RoundEnvironment) Proxy.newProxyInstance(
                RoundEnvironment.class.getClassLoader(),
                new Class<?>[]{RoundEnvironment.class},
                (proxy, method, args) -> {
                    if ("getElementsAnnotatedWith".equals(method.getName())
                            && args != null && args.length == 1 && args[0] instanceof Class<?> cls
                            && cls == TaggedValue.class) {
                        return (Set<Element>) (Set<?>) elements;
                    }
                    if ("getRootElements".equals(method.getName())) {
                        return Set.of();
                    }
                    throw new UnsupportedOperationException(
                            "Stub RoundEnvironment does not support: " + method.getName());
                }
        );
    }

    /**
     * Creates a stub {@link ProcessingEnvironment} whose {@code getElementUtils()}
     * returns an {@link javax.lang.model.util.Elements} that supports
     * {@code getElementValuesWithDefaults(AnnotationMirror)}.
     */
    private static ProcessingEnvironment createProcessingEnvironmentStub() {
        javax.lang.model.util.Elements elementsStub = createElementsStub();
        return (ProcessingEnvironment) Proxy.newProxyInstance(
                ProcessingEnvironment.class.getClassLoader(),
                new Class<?>[]{ProcessingEnvironment.class},
                (proxy, method, args) -> {
                    if ("getElementUtils".equals(method.getName())) {
                        return elementsStub;
                    }
                    throw new UnsupportedOperationException(
                            "Stub ProcessingEnvironment does not support: " + method.getName());
                }
        );
    }

    /**
     * Creates a stub {@link javax.lang.model.util.Elements} that supports
     * {@code getElementValuesWithDefaults(AnnotationMirror)}.
     * The stub delegates to the annotation mirror's own element values map
     * (which our stubs already populate with defaults).
     */
    private static javax.lang.model.util.Elements createElementsStub() {
        return (javax.lang.model.util.Elements) Proxy.newProxyInstance(
                javax.lang.model.util.Elements.class.getClassLoader(),
                new Class<?>[]{javax.lang.model.util.Elements.class},
                (proxy, method, args) -> {
                    if ("getElementValuesWithDefaults".equals(method.getName())
                            && args != null && args.length == 1
                            && args[0] instanceof AnnotationMirror mirror) {
                        // Our stub mirrors store the full map including defaults
                        return mirror.getElementValues();
                    }
                    throw new UnsupportedOperationException(
                            "Stub Elements does not support: " + method.getName());
                }
        );
    }

    /**
     * Creates a stub {@link TypeElement} representing an annotation type
     * that is annotated with {@code @TaggedValue(strict = strictValue)}.
     */
    private static TypeElement createTypeElementStub(String fqn, boolean strictValue) {
        // Build the @TaggedValue annotation mirror
        AnnotationMirror taggedValueMirror = createTaggedValueMirrorStub(strictValue);

        return (TypeElement) Proxy.newProxyInstance(
                TypeElement.class.getClassLoader(),
                new Class<?>[]{TypeElement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getQualifiedName" -> createNameStub(fqn);
                    case "getAnnotationMirrors" -> List.of(taggedValueMirror);
                    case "getKind" -> ElementKind.ANNOTATION_TYPE;
                    case "hashCode" -> fqn.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "TypeElement[" + fqn + "]";
                    default -> throw new UnsupportedOperationException(
                            "Stub TypeElement does not support: " + method.getName());
                }
        );
    }

    /**
     * Creates a stub {@link AnnotationMirror} representing {@code @TaggedValue(strict = value)}.
     */
    private static AnnotationMirror createTaggedValueMirrorStub(boolean strictValue) {
        // Create the annotation type (DeclaredType whose asElement() returns a TypeElement with FQN "rawit.TaggedValue")
        TypeElement taggedValueTypeElement = createSimpleTypeElementStub(TAGGED_VALUE_FQN);
        DeclaredType annotationType = createDeclaredTypeStub(taggedValueTypeElement);

        // Create the "strict" executable element key
        ExecutableElement strictKey = createExecutableElementStub("strict");

        // Create the annotation value for strict
        AnnotationValue strictAnnotationValue = createAnnotationValueStub(strictValue);

        // Build the element values map
        Map<ExecutableElement, AnnotationValue> elementValues = new LinkedHashMap<>();
        elementValues.put(strictKey, strictAnnotationValue);

        return (AnnotationMirror) Proxy.newProxyInstance(
                AnnotationMirror.class.getClassLoader(),
                new Class<?>[]{AnnotationMirror.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAnnotationType" -> annotationType;
                    case "getElementValues" -> elementValues;
                    default -> throw new UnsupportedOperationException(
                            "Stub AnnotationMirror does not support: " + method.getName());
                }
        );
    }

    /**
     * Creates a simple stub {@link TypeElement} that only supports {@code getQualifiedName()}.
     */
    private static TypeElement createSimpleTypeElementStub(String fqn) {
        return (TypeElement) Proxy.newProxyInstance(
                TypeElement.class.getClassLoader(),
                new Class<?>[]{TypeElement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getQualifiedName" -> createNameStub(fqn);
                    case "hashCode" -> fqn.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "SimpleTypeElement[" + fqn + "]";
                    default -> throw new UnsupportedOperationException(
                            "Stub simple TypeElement does not support: " + method.getName());
                }
        );
    }

    /**
     * Creates a stub {@link DeclaredType} whose {@code asElement()} returns the given element.
     */
    private static DeclaredType createDeclaredTypeStub(TypeElement element) {
        return (DeclaredType) Proxy.newProxyInstance(
                DeclaredType.class.getClassLoader(),
                new Class<?>[]{DeclaredType.class},
                (proxy, method, args) -> {
                    if ("asElement".equals(method.getName())) {
                        return element;
                    }
                    throw new UnsupportedOperationException(
                            "Stub DeclaredType does not support: " + method.getName());
                }
        );
    }

    /**
     * Creates a stub {@link ExecutableElement} whose {@code getSimpleName()} returns the given name.
     * Also supports {@code hashCode}, {@code equals}, and {@code toString} for use as map keys.
     */
    private static ExecutableElement createExecutableElementStub(String name) {
        return (ExecutableElement) Proxy.newProxyInstance(
                ExecutableElement.class.getClassLoader(),
                new Class<?>[]{ExecutableElement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSimpleName" -> createNameStub(name);
                    case "hashCode" -> name.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "ExecutableElement[" + name + "]";
                    default -> throw new UnsupportedOperationException(
                            "Stub ExecutableElement does not support: " + method.getName());
                }
        );
    }

    /**
     * Creates a stub {@link AnnotationValue} wrapping the given value.
     */
    private static AnnotationValue createAnnotationValueStub(Object value) {
        return (AnnotationValue) Proxy.newProxyInstance(
                AnnotationValue.class.getClassLoader(),
                new Class<?>[]{AnnotationValue.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getValue" -> value;
                    case "toString" -> String.valueOf(value);
                    default -> throw new UnsupportedOperationException(
                            "Stub AnnotationValue does not support: " + method.getName());
                }
        );
    }

    /**
     * Creates a stub {@link javax.lang.model.element.Name} for the given string.
     */
    private static Name createNameStub(String value) {
        return (Name) Proxy.newProxyInstance(
                Name.class.getClassLoader(),
                new Class<?>[]{Name.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> value;
                    case "contentEquals" -> {
                        if (args[0] instanceof CharSequence cs) {
                            yield value.contentEquals(cs);
                        }
                        yield false;
                    }
                    case "length" -> value.length();
                    case "charAt" -> value.charAt((int) args[0]);
                    case "subSequence" -> value.subSequence((int) args[0], (int) args[1]);
                    case "equals" -> {
                        if (args[0] instanceof CharSequence cs) {
                            yield value.contentEquals(cs);
                        }
                        yield false;
                    }
                    case "hashCode" -> value.hashCode();
                    default -> throw new UnsupportedOperationException(
                            "Stub Name does not support: " + method.getName());
                }
        );
    }
}
