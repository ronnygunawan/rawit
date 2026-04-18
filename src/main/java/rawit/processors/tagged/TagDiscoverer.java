package rawit.processors.tagged;

import rawit.TaggedValue;
import rawit.processors.model.TagInfo;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Discovers annotation types annotated with {@link TaggedValue @TaggedValue}
 * in the current compilation round and builds a tag info map.
 *
 * <p>For each discovered tag annotation, the discoverer extracts the
 * {@code strict} attribute value and constructs a {@link TagInfo} record
 * keyed by the annotation's fully qualified name.
 */
public final class TagDiscoverer {

    /**
     * Scans the round environment for annotation types annotated with
     * {@code @TaggedValue} and builds a tag info map.
     *
     * <p>Discovery works in two passes:
     * <ol>
     *   <li>Direct: finds annotation types annotated with {@code @TaggedValue}
     *       that are being compiled in the current round.</li>
     *   <li>Indirect: scans annotations on all root elements in the round and
     *       checks whether any of those annotation types carry {@code @TaggedValue}.
     *       This catches tag annotations that come from pre-compiled JARs on the
     *       classpath (where the tag annotation declaration is not in the current
     *       round but its usages are).</li>
     * </ol>
     *
     * @param roundEnv      the current round environment
     * @param processingEnv the processing environment
     * @return map from tag annotation FQN to {@link TagInfo}
     */
    public Map<String, TagInfo> discover(
            final RoundEnvironment roundEnv,
            final ProcessingEnvironment processingEnv
    ) {
        final Map<String, TagInfo> tagMap = new LinkedHashMap<>();

        // Pass 1: Direct discovery — tag annotations compiled in this round
        for (final Element element : roundEnv.getElementsAnnotatedWith(TaggedValue.class)) {
            if (!(element instanceof TypeElement typeElement)) {
                continue;
            }

            final String fqn = typeElement.getQualifiedName().toString();
            final boolean strict = extractStrictAttribute(typeElement, processingEnv);
            tagMap.put(fqn, new TagInfo(fqn, strict));
        }

        // Pass 2: Indirect discovery — scan annotations on root elements to find
        // tag annotations from pre-compiled JARs on the classpath
        for (final Element rootElement : roundEnv.getRootElements()) {
            discoverFromElementAnnotations(rootElement, tagMap, processingEnv);
        }

        return tagMap;
    }

    /**
     * Recursively inspects annotations on an element (and its enclosed elements)
     * to discover tag annotations from the classpath.
     */
    private void discoverFromElementAnnotations(
            final Element element,
            final Map<String, TagInfo> tagMap,
            final ProcessingEnvironment processingEnv
    ) {
        for (final AnnotationMirror mirror : element.getAnnotationMirrors()) {
            final Element annotationElement = mirror.getAnnotationType().asElement();
            if (annotationElement instanceof TypeElement annotationType) {
                final String fqn = annotationType.getQualifiedName().toString();
                if (!tagMap.containsKey(fqn) && hasTaggedValueAnnotation(annotationType)) {
                    final boolean strict = extractStrictAttribute(annotationType, processingEnv);
                    tagMap.put(fqn, new TagInfo(fqn, strict));
                }
            }
        }

        for (final Element enclosed : element.getEnclosedElements()) {
            discoverFromElementAnnotations(enclosed, tagMap, processingEnv);
        }
    }

    /**
     * Checks whether a type element carries the {@code @TaggedValue} annotation.
     */
    private static boolean hasTaggedValueAnnotation(final TypeElement typeElement) {
        final String taggedValueFqn = TaggedValue.class.getCanonicalName();
        for (final AnnotationMirror mirror : typeElement.getAnnotationMirrors()) {
            final Element annotationType = mirror.getAnnotationType().asElement();
            if (annotationType instanceof TypeElement te
                    && te.getQualifiedName().contentEquals(taggedValueFqn)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts the {@code strict} attribute value from the {@code @TaggedValue}
     * annotation on the given element.
     *
     * <p>Uses {@link AnnotationMirror} inspection rather than
     * {@link Element#getAnnotation(Class)} to work reliably with
     * {@code CLASS}-retained annotations during annotation processing.
     *
     * @param element       the annotation type element annotated with {@code @TaggedValue}
     * @param processingEnv the processing environment
     * @return the value of the {@code strict} attribute, or {@code false} if not found
     */
    private boolean extractStrictAttribute(
            final TypeElement element,
            final ProcessingEnvironment processingEnv
    ) {
        final String taggedValueFqn = TaggedValue.class.getCanonicalName();

        for (final AnnotationMirror mirror : element.getAnnotationMirrors()) {
            final TypeElement annotationType =
                    (TypeElement) mirror.getAnnotationType().asElement();
            if (!annotationType.getQualifiedName().contentEquals(taggedValueFqn)) {
                continue;
            }

            // Use getElementValuesWithDefaults to include default attribute values
            final Map<? extends ExecutableElement, ? extends AnnotationValue> values =
                    processingEnv.getElementUtils()
                            .getElementValuesWithDefaults(mirror);

            for (final Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                    : values.entrySet()) {
                if ("strict".contentEquals(entry.getKey().getSimpleName())) {
                    final Object value = entry.getValue().getValue();
                    if (value instanceof Boolean b) {
                        return b;
                    }
                }
            }
        }

        // Default: lax mode
        return false;
    }
}
