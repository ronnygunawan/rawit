package rawit.processors.tagged;

import net.jqwik.api.*;
import rawit.processors.model.TagInfo;
import rawit.processors.model.TagResolution;

import javax.annotation.processing.Messager;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for {@link TagResolver}.
 *
 * <p>Each property runs a minimum of 100 iterations via jqwik.
 *
 * <p>Uses Java {@link Proxy} stubs to simulate annotation processing types,
 * following the same approach as {@code TagDiscovererPropertyTest}.
 */
// Feature: tagged-value-annotation, Property 2: Tag resolution recognizes tags on all supported element kinds
// Feature: tagged-value-annotation, Property 8: Multiple tags use first encountered and emit duplicate warning
class TagResolverPropertyTest {

    private static final String[] FQN_POOL = {
            "com.example.UserId", "com.example.FirstName", "com.example.LastName",
            "com.example.Email", "com.example.PhoneNumber", "org.acme.OrderId",
            "org.acme.ProductId", "io.test.Amount", "io.test.Currency"
    };

    private static final ElementKind[] SUPPORTED_ELEMENT_KINDS = {
            ElementKind.FIELD,
            ElementKind.PARAMETER,
            ElementKind.LOCAL_VARIABLE,
            ElementKind.METHOD,
            ElementKind.RECORD_COMPONENT
    };

    private final TagResolver resolver = new TagResolver();

    // -------------------------------------------------------------------------
    // Model for test data
    // -------------------------------------------------------------------------

    /**
     * Represents a simulated annotation on an element — may or may not be
     * a recognized tag in the tag map.
     */
    private record SimAnnotation(String fqn) {}

    // -------------------------------------------------------------------------
    // Arbitraries
    // -------------------------------------------------------------------------

    @Provide
    Arbitrary<ElementKind> elementKinds() {
        return Arbitraries.of(SUPPORTED_ELEMENT_KINDS);
    }

    @Provide
    Arbitrary<String> tagFqns() {
        return Arbitraries.of(FQN_POOL);
    }

    @Provide
    Arbitrary<Boolean> strictValues() {
        return Arbitraries.of(true, false);
    }

    /**
     * Generates a tag map with 1–5 entries from the FQN pool.
     */
    @Provide
    Arbitrary<Map<String, TagInfo>> tagMaps() {
        return Combinators.combine(
                Arbitraries.of(FQN_POOL),
                Arbitraries.of(true, false)
        ).as((fqn, strict) -> new TagInfo(fqn, strict))
                .list().ofMinSize(1).ofMaxSize(5)
                .map(list -> {
                    Map<String, TagInfo> map = new LinkedHashMap<>();
                    for (TagInfo info : list) {
                        map.putIfAbsent(info.annotationFqn(), info);
                    }
                    return map;
                });
    }

    // -------------------------------------------------------------------------
    // Property 2: Tag resolution recognizes tags on all supported element kinds
    // Feature: tagged-value-annotation, Property 2: Tag resolution recognizes tags on all supported element kinds
    // -------------------------------------------------------------------------

    /**
     * Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5
     *
     * For any element kind and any element with 0 or 1 recognized tag annotation,
     * the resolver returns the correct TagResolution.
     */
    @Property(tries = 100)
    void property2_tagResolutionRecognizesTagsOnAllSupportedElementKinds(
            @ForAll("elementKinds") ElementKind kind,
            @ForAll("tagMaps") Map<String, TagInfo> tagMap,
            @ForAll boolean hasTag
    ) {
        // Pick a tag from the map if hasTag is true
        TagInfo expectedTag = null;
        List<String> annotationFqns = new ArrayList<>();

        if (hasTag && !tagMap.isEmpty()) {
            // Pick the first tag from the map
            expectedTag = tagMap.values().iterator().next();
            annotationFqns.add(expectedTag.annotationFqn());
        }

        // Add some non-tag annotations for noise
        annotationFqns.add(0, "javax.annotation.Nonnull");

        // Create the element stub
        Element element = createElementStub(kind, annotationFqns);

        // Create a no-op messager (no warnings expected for 0 or 1 tag)
        RecordingMessager messager = new RecordingMessager();

        // Resolve
        TagResolution result = resolver.resolve(element, tagMap, messager.proxy());

        if (expectedTag != null) {
            assertInstanceOf(TagResolution.Tagged.class, result,
                    "Element with a recognized tag annotation should resolve to Tagged");
            TagResolution.Tagged tagged = (TagResolution.Tagged) result;
            assertEquals(expectedTag.annotationFqn(), tagged.tag().annotationFqn(),
                    "Resolved tag FQN must match");
            assertEquals(expectedTag.strict(), tagged.tag().strict(),
                    "Resolved tag strict value must match");
        } else {
            assertInstanceOf(TagResolution.Untagged.class, result,
                    "Element without a recognized tag annotation should resolve to Untagged");
        }

        // No duplicate warning should be emitted for 0 or 1 tag
        assertNull(messager.lastWarning(),
                "No duplicate-tag warning should be emitted for 0 or 1 recognized tag");
    }

    // -------------------------------------------------------------------------
    // Property 8: Multiple tags use first encountered and emit duplicate warning
    // Feature: tagged-value-annotation, Property 8: Multiple tags use first encountered and emit duplicate warning
    // -------------------------------------------------------------------------

    /**
     * Validates: Requirements 11.1, 11.2
     *
     * For any element carrying 2+ recognized tag annotations, the resolver
     * returns Tagged with the first tag encountered and emits a duplicate warning.
     */
    @Property(tries = 100)
    void property8_multipleTagsUseFirstEncounteredAndEmitDuplicateWarning(
            @ForAll("elementKinds") ElementKind kind,
            @ForAll("strictValues") boolean strict1,
            @ForAll("strictValues") boolean strict2
    ) {
        // Create two distinct tag annotations
        String fqn1 = FQN_POOL[0]; // com.example.UserId
        String fqn2 = FQN_POOL[1]; // com.example.FirstName

        TagInfo tag1 = new TagInfo(fqn1, strict1);
        TagInfo tag2 = new TagInfo(fqn2, strict2);

        Map<String, TagInfo> tagMap = new LinkedHashMap<>();
        tagMap.put(fqn1, tag1);
        tagMap.put(fqn2, tag2);

        // Element has both tag annotations (in order: fqn1, fqn2)
        List<String> annotationFqns = List.of(fqn1, fqn2);
        Element element = createElementStub(kind, annotationFqns);

        RecordingMessager messager = new RecordingMessager();

        TagResolution result = resolver.resolve(element, tagMap, messager.proxy());

        // Should return Tagged with the first tag
        assertInstanceOf(TagResolution.Tagged.class, result,
                "Element with multiple recognized tags should resolve to Tagged");
        TagResolution.Tagged tagged = (TagResolution.Tagged) result;
        assertEquals(fqn1, tagged.tag().annotationFqn(),
                "First tag encountered should be used as the effective tag");
        assertEquals(strict1, tagged.tag().strict(),
                "Strict value should match the first tag");

        // Should have emitted a duplicate-tag warning
        assertNotNull(messager.lastWarning(),
                "A duplicate-tag warning must be emitted when multiple tags are present");
        assertEquals(Diagnostic.Kind.WARNING, messager.lastWarningKind(),
                "Duplicate-tag warning must use Diagnostic.Kind.WARNING");

        // Warning message should mention both tag names
        String warningMsg = messager.lastWarning();
        String simpleName1 = fqn1.substring(fqn1.lastIndexOf('.') + 1);
        String simpleName2 = fqn2.substring(fqn2.lastIndexOf('.') + 1);
        assertTrue(warningMsg.contains(simpleName1),
                "Warning message should mention the first tag: " + simpleName1);
        assertTrue(warningMsg.contains(simpleName2),
                "Warning message should mention the second tag: " + simpleName2);
    }

    // -------------------------------------------------------------------------
    // Stub factories (Java Proxy approach)
    // -------------------------------------------------------------------------

    /**
     * Creates a stub {@link Element} with the given kind and annotation mirrors.
     */
    private static Element createElementStub(ElementKind kind, List<String> annotationFqns) {
        List<AnnotationMirror> mirrors = annotationFqns.stream()
                .map(TagResolverPropertyTest::createAnnotationMirrorStub)
                .toList();

        return (Element) Proxy.newProxyInstance(
                Element.class.getClassLoader(),
                new Class<?>[]{Element.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getKind" -> kind;
                    case "getAnnotationMirrors" -> mirrors;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Element[" + kind + ", annotations=" + annotationFqns + "]";
                    default -> throw new UnsupportedOperationException(
                            "Stub Element does not support: " + method.getName());
                }
        );
    }

    /**
     * Creates a stub {@link AnnotationMirror} whose annotation type resolves
     * to a {@link TypeElement} with the given FQN.
     */
    private static AnnotationMirror createAnnotationMirrorStub(String fqn) {
        TypeElement typeElement = createSimpleTypeElementStub(fqn);
        DeclaredType annotationType = createDeclaredTypeStub(typeElement);

        return (AnnotationMirror) Proxy.newProxyInstance(
                AnnotationMirror.class.getClassLoader(),
                new Class<?>[]{AnnotationMirror.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAnnotationType" -> annotationType;
                    case "getElementValues" -> Map.of();
                    case "toString" -> "@" + fqn;
                    default -> throw new UnsupportedOperationException(
                            "Stub AnnotationMirror does not support: " + method.getName());
                }
        );
    }

    /**
     * Creates a simple stub {@link TypeElement} that supports {@code getQualifiedName()}
     * and {@code getAnnotationMirrors()} (returns empty list for non-tag annotations).
     */
    private static TypeElement createSimpleTypeElementStub(String fqn) {
        return (TypeElement) Proxy.newProxyInstance(
                TypeElement.class.getClassLoader(),
                new Class<?>[]{TypeElement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getQualifiedName" -> createNameStub(fqn);
                    case "getAnnotationMirrors" -> List.of();
                    case "hashCode" -> fqn.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "TypeElement[" + fqn + "]";
                    default -> throw new UnsupportedOperationException(
                            "Stub TypeElement does not support: " + method.getName());
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
     * Creates a stub {@link Name} for the given string.
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

    // -------------------------------------------------------------------------
    // Recording Messager helper
    // -------------------------------------------------------------------------

    /**
     * A helper that records the last warning message emitted via a stub {@link Messager}.
     */
    private static final class RecordingMessager {
        private final AtomicReference<String> lastWarningMsg = new AtomicReference<>();
        private final AtomicReference<Diagnostic.Kind> lastWarningKindRef = new AtomicReference<>();

        Messager proxy() {
            return (Messager) Proxy.newProxyInstance(
                    Messager.class.getClassLoader(),
                    new Class<?>[]{Messager.class},
                    (proxy, method, args) -> {
                        if ("printMessage".equals(method.getName())) {
                            Diagnostic.Kind msgKind = (Diagnostic.Kind) args[0];
                            String msg = (String) args[1];
                            lastWarningKindRef.set(msgKind);
                            lastWarningMsg.set(msg);
                            return null;
                        }
                        throw new UnsupportedOperationException(
                                "Stub Messager does not support: " + method.getName());
                    }
            );
        }

        String lastWarning() {
            return lastWarningMsg.get();
        }

        Diagnostic.Kind lastWarningKind() {
            return lastWarningKindRef.get();
        }
    }
}
