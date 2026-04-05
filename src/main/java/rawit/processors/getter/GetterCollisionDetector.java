package rawit.processors.getter;

import rawit.processors.model.AnnotatedField;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects getter name collisions across four dimensions:
 * <ol>
 *   <li>Same-class zero-param method with the same name as a computed getter.</li>
 *   <li>Inter-getter collision — two {@code @Getter} fields produce the same getter name.</li>
 *   <li>Inherited zero-param method from a superclass (not {@code @Getter}-generated).</li>
 *   <li>Covariant return type validation in field-hiding scenarios.</li>
 * </ol>
 *
 * <p>All errors are emitted via the supplied {@link Messager}. Fields that fail any check
 * are excluded from the returned list.
 */
public class GetterCollisionDetector {

    /** Creates a new {@code GetterCollisionDetector}. */
    public GetterCollisionDetector() {}

    /**
     * Detects collisions among the given {@code @Getter}-annotated fields and returns
     * only those that passed all checks.
     *
     * @param fields          all {@code @Getter}-annotated fields in the same enclosing class
     * @param enclosingClass  the {@link TypeElement} of the enclosing class
     * @param messager        compiler messager for emitting diagnostics
     * @param typeUtils       type utilities for subtype checks
     * @return the subset of fields that passed all collision checks
     */
    public List<AnnotatedField> detect(
            List<AnnotatedField> fields,
            TypeElement enclosingClass,
            Messager messager,
            Types typeUtils) {

        final Set<String> rejected = new HashSet<>();

        // Check 2: Inter-getter collision — two fields produce the same getter name
        checkInterGetterCollisions(fields, enclosingClass, messager, rejected);

        // Check 1: Same-class zero-param method with same name
        checkSameClassMethodCollisions(fields, enclosingClass, messager, rejected);

        // Check 3: Inherited zero-param method from superclass
        checkInheritedMethodCollisions(fields, enclosingClass, messager, typeUtils, rejected);

        // Check 4: Covariant return type validation in field-hiding scenarios
        checkCovariantReturnTypes(fields, enclosingClass, messager, typeUtils, rejected);

        final List<AnnotatedField> passed = new ArrayList<>();
        for (final AnnotatedField field : fields) {
            if (!rejected.contains(field.fieldName())) {
                passed.add(field);
            }
        }
        return passed;
    }

    /**
     * Check 2: Two or more {@code @Getter} fields in the same class produce the same getter name.
     */
    private void checkInterGetterCollisions(
            List<AnnotatedField> fields,
            TypeElement enclosingClass,
            Messager messager,
            Set<String> rejected) {

        final Map<String, List<AnnotatedField>> byGetterName = new LinkedHashMap<>();
        for (final AnnotatedField field : fields) {
            byGetterName.computeIfAbsent(field.getterName(), k -> new ArrayList<>()).add(field);
        }

        final String className = enclosingClass.getQualifiedName().toString();
        for (final Map.Entry<String, List<AnnotatedField>> entry : byGetterName.entrySet()) {
            if (entry.getValue().size() > 1) {
                for (final AnnotatedField field : entry.getValue()) {
                    messager.printMessage(
                            Diagnostic.Kind.ERROR,
                            "getter '" + entry.getKey() + "()' conflicts with another @Getter field in " + className,
                            findFieldElement(enclosingClass, field.fieldName()));
                    rejected.add(field.fieldName());
                }
            }
        }
    }

    /**
     * Check 1: A zero-parameter method with the same name already exists in the enclosing class.
     */
    private void checkSameClassMethodCollisions(
            List<AnnotatedField> fields,
            TypeElement enclosingClass,
            Messager messager,
            Set<String> rejected) {

        final Set<String> existingZeroParamMethods = collectZeroParamMethodNames(enclosingClass);
        final String className = enclosingClass.getQualifiedName().toString();

        for (final AnnotatedField field : fields) {
            if (rejected.contains(field.fieldName())) {
                continue;
            }
            if (existingZeroParamMethods.contains(field.getterName())) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "getter '" + field.getterName() + "()' conflicts with existing method in " + className,
                        findFieldElement(enclosingClass, field.fieldName()));
                rejected.add(field.fieldName());
            }
        }
    }

    /**
     * Check 3: A zero-parameter method with the same name is inherited from a superclass.
     */
    private void checkInheritedMethodCollisions(
            List<AnnotatedField> fields,
            TypeElement enclosingClass,
            Messager messager,
            Types typeUtils,
            Set<String> rejected) {

        final Set<String> getterNames = new HashSet<>();
        for (final AnnotatedField field : fields) {
            getterNames.add(field.getterName());
        }

        // Walk the superclass chain looking for zero-param methods that match getter names
        final Map<String, String> inheritedMethodSources = new LinkedHashMap<>();
        TypeMirror superMirror = enclosingClass.getSuperclass();
        while (superMirror instanceof DeclaredType declaredType) {
            final TypeElement superElement = (TypeElement) declaredType.asElement();
            // Skip java.lang.Object
            if (superElement.getQualifiedName().contentEquals("java.lang.Object")) {
                break;
            }

            for (final Element enclosed : superElement.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.METHOD) {
                    continue;
                }
                final ExecutableElement method = (ExecutableElement) enclosed;
                final String methodName = method.getSimpleName().toString();
                if (method.getParameters().isEmpty()
                        && getterNames.contains(methodName)
                        && !inheritedMethodSources.containsKey(methodName)
                        && !isGetterAnnotatedField(superElement, methodName)) {
                    inheritedMethodSources.put(methodName, superElement.getQualifiedName().toString());
                }
            }
            superMirror = superElement.getSuperclass();
        }

        for (final AnnotatedField field : fields) {
            if (rejected.contains(field.fieldName())) {
                continue;
            }
            final String superclass = inheritedMethodSources.get(field.getterName());
            if (superclass != null) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "getter '" + field.getterName() + "()' conflicts with inherited method from " + superclass,
                        findFieldElement(enclosingClass, field.fieldName()));
                rejected.add(field.fieldName());
            }
        }
    }

    /**
     * Check 4: Covariant return type validation in field-hiding scenarios.
     *
     * <p>When a derived class hides a base class field and both are {@code @Getter}-annotated,
     * the derived field type must be a subtype of the base field type. If not, emit an error.
     */
    private void checkCovariantReturnTypes(
            List<AnnotatedField> fields,
            TypeElement enclosingClass,
            Messager messager,
            Types typeUtils,
            Set<String> rejected) {

        // Build a map of getter name → field for the current class
        final Map<String, AnnotatedField> currentGetters = new LinkedHashMap<>();
        for (final AnnotatedField field : fields) {
            if (!rejected.contains(field.fieldName())) {
                currentGetters.put(field.getterName(), field);
            }
        }

        if (currentGetters.isEmpty()) {
            return;
        }

        // Walk the superclass chain looking for @Getter-annotated fields that produce the same getter name
        final String derivedClassName = enclosingClass.getQualifiedName().toString();
        TypeMirror superMirror = enclosingClass.getSuperclass();
        while (superMirror instanceof DeclaredType declaredType) {
            final TypeElement superElement = (TypeElement) declaredType.asElement();
            if (superElement.getQualifiedName().contentEquals("java.lang.Object")) {
                break;
            }

            for (final Element enclosed : superElement.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.FIELD) {
                    continue;
                }
                final VariableElement superField = (VariableElement) enclosed;
                if (superField.getAnnotation(rawit.Getter.class) == null) {
                    continue;
                }

                // Compute the getter name for the super field
                final String superGetterName = new GetterNameResolver().resolve(
                        superField.getSimpleName().toString(),
                        typeDescriptorOf(superField));

                final AnnotatedField derivedField = currentGetters.get(superGetterName);
                if (derivedField == null) {
                    continue;
                }

                // Both fields produce the same getter name — check return type compatibility
                final TypeMirror derivedType = findFieldType(enclosingClass, derivedField.fieldName());
                final TypeMirror baseType = superField.asType();

                if (derivedType != null && baseType != null
                        && !typeUtils.isSubtype(typeUtils.erasure(derivedType), typeUtils.erasure(baseType))) {
                    final String baseClassName = superElement.getQualifiedName().toString();
                    messager.printMessage(
                            Diagnostic.Kind.ERROR,
                            "getter '" + superGetterName + "()' in " + derivedClassName
                                    + " cannot override getter in " + baseClassName
                                    + ": incompatible return types "
                                    + derivedType + " and " + baseType,
                            findFieldElement(enclosingClass, derivedField.fieldName()));
                    rejected.add(derivedField.fieldName());
                }
            }
            superMirror = superElement.getSuperclass();
        }
    }

    // ---- helpers ----

    /**
     * Checks whether the given superclass has a {@code @Getter}-annotated field whose
     * computed getter name matches the given method name. This is used to distinguish
     * inherited methods that were generated by {@code @Getter} from manually declared ones.
     */
    private boolean isGetterAnnotatedField(TypeElement typeElement, String getterName) {
        for (final Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) {
                continue;
            }
            final VariableElement field = (VariableElement) enclosed;
            if (field.getAnnotation(rawit.Getter.class) == null) {
                continue;
            }
            final String computedName = new GetterNameResolver().resolve(
                    field.getSimpleName().toString(),
                    typeDescriptorOf(field));
            if (computedName.equals(getterName)) {
                return true;
            }
        }
        return false;
    }

    /** Collects the names of all zero-parameter methods declared directly in the given type. */
    private Set<String> collectZeroParamMethodNames(TypeElement typeElement) {
        final Set<String> names = new HashSet<>();
        for (final Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            final ExecutableElement method = (ExecutableElement) enclosed;
            if (method.getParameters().isEmpty()) {
                names.add(method.getSimpleName().toString());
            }
        }
        return names;
    }

    /** Finds the {@link Element} for a field by name within the enclosing class. */
    private Element findFieldElement(TypeElement enclosingClass, String fieldName) {
        for (final Element enclosed : enclosingClass.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD
                    && enclosed.getSimpleName().contentEquals(fieldName)) {
                return enclosed;
            }
        }
        // Fallback to the class itself if the field element is not found
        return enclosingClass;
    }

    /** Finds the {@link TypeMirror} for a field by name within the enclosing class. */
    private TypeMirror findFieldType(TypeElement enclosingClass, String fieldName) {
        for (final Element enclosed : enclosingClass.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD
                    && enclosed.getSimpleName().contentEquals(fieldName)) {
                return enclosed.asType();
            }
        }
        return null;
    }

    /**
     * Computes a simplified JVM type descriptor for a {@link VariableElement}.
     * This is used only for getter name resolution (primitive boolean detection).
     */
    private String typeDescriptorOf(VariableElement field) {
        final TypeMirror type = field.asType();
        return switch (type.getKind()) {
            case BOOLEAN -> "Z";
            case BYTE -> "B";
            case CHAR -> "C";
            case SHORT -> "S";
            case INT -> "I";
            case LONG -> "J";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            default -> "L" + type.toString().replace('.', '/') + ";";
        };
    }
}
