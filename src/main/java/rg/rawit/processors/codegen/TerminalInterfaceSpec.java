package rg.rawit.processors.codegen;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import rg.rawit.processors.model.AnnotatedMethod;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Builds the terminal interface {@code TypeSpec} for a staged invocation chain.
 *
 * <ul>
 *   <li>For {@code @Invoker}: generates {@code InvokeStageInvoker} with a zero-arg {@code invoke()}
 *       method returning the annotated method's return type.</li>
 *   <li>For {@code @Constructor}: generates {@code ConstructStageInvoker} with a zero-arg
 *       {@code construct()} method returning the enclosing class type.</li>
 * </ul>
 *
 * <p>Checked exceptions declared on the annotated element are propagated to the terminal method.
 * The interface is annotated with {@code @FunctionalInterface}.
 */
public class TerminalInterfaceSpec {

    private final AnnotatedMethod method;

    public TerminalInterfaceSpec(final AnnotatedMethod method) {
        this.method = method;
    }

    /**
     * Builds and returns the terminal interface {@link TypeSpec}.
     *
     * @return the {@code InvokeStageInvoker} or {@code ConstructStageInvoker} interface spec
     */
    public TypeSpec build() {
        if (method.isConstructorAnnotation()) {
            // @Constructor: use ConstructStageInvoker with construct() method
            return buildConstructStageInvoker();
        }
        // @Invoker (including @Invoker on constructors): use InvokeStageInvoker with invoke() method
        return buildInvokeStageInvoker();
    }

    // -------------------------------------------------------------------------
    // InvokeStageInvoker — for @Invoker
    // -------------------------------------------------------------------------

    private TypeSpec buildInvokeStageInvoker() {
        final TypeName returnType;
        if (method.isConstructor()) {
            // @Invoker on a constructor: invoke() returns the enclosing class instance
            returnType = binaryNameToClassName(method.enclosingClassName());
        } else {
            returnType = descriptorToTypeName(method.returnTypeDescriptor());
        }
        final MethodSpec invokeMethod = buildTerminalMethod("invoke", returnType);

        return TypeSpec.interfaceBuilder("InvokeStageInvoker")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(FunctionalInterface.class)
                .addMethod(invokeMethod)
                .build();
    }

    // -------------------------------------------------------------------------
    // ConstructStageInvoker — for @Constructor
    // -------------------------------------------------------------------------

    private TypeSpec buildConstructStageInvoker() {
        // Return type is the enclosing class itself
        final TypeName returnType = binaryNameToClassName(method.enclosingClassName());
        final MethodSpec constructMethod = buildTerminalMethod("construct", returnType);

        return TypeSpec.interfaceBuilder("ConstructStageInvoker")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(FunctionalInterface.class)
                .addMethod(constructMethod)
                .build();
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private MethodSpec buildTerminalMethod(final String methodName, final TypeName returnType) {
        final MethodSpec.Builder mb = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(returnType);

        for (final String exceptionBinaryName : method.checkedExceptions()) {
            mb.addException(binaryNameToClassName(exceptionBinaryName));
        }

        return mb.build();
    }

    // -------------------------------------------------------------------------
    // Type descriptor / binary name utilities
    // -------------------------------------------------------------------------

    /**
     * Converts a JVM type descriptor to a JavaPoet {@link TypeName}.
     *
     * <p>Primitives use their one-character descriptors; object types use {@code L<binary>;} form;
     * array types use {@code [} prefix and are handled recursively.
     */
    static TypeName descriptorToTypeName(final String descriptor) {
        return switch (descriptor) {
            case "V" -> TypeName.VOID;
            case "I" -> TypeName.INT;
            case "J" -> TypeName.LONG;
            case "F" -> TypeName.FLOAT;
            case "D" -> TypeName.DOUBLE;
            case "Z" -> TypeName.BOOLEAN;
            case "B" -> TypeName.BYTE;
            case "C" -> TypeName.CHAR;
            case "S" -> TypeName.SHORT;
            default -> {
                if (descriptor.startsWith("[")) {
                    // Array type — recurse on the component descriptor
                    final TypeName component = descriptorToTypeName(descriptor.substring(1));
                    yield com.squareup.javapoet.ArrayTypeName.of(component);
                }
                if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
                    final String binaryName = descriptor.substring(1, descriptor.length() - 1);
                    yield binaryNameToClassName(binaryName);
                }
                // Unknown / type variable — erase to Object
                yield ClassName.OBJECT;
            }
        };
    }

    static ClassName binaryNameToClassName(final String binaryName) {
        final String dotName = binaryName.replace('/', '.');
        final int lastDot = dotName.lastIndexOf('.');
        final String packageName = lastDot < 0 ? "" : dotName.substring(0, lastDot);
        final String simpleBinaryName = lastDot < 0 ? dotName : dotName.substring(lastDot + 1);

        // Split on '$' to separate outer and nested class names for JavaPoet
        final String[] simpleNames = simpleBinaryName.split("\\$");
        if (simpleNames.length <= 1) {
            return ClassName.get(packageName, simpleBinaryName);
        }
        final String outer = simpleNames[0];
        final String[] nested = new String[simpleNames.length - 1];
        System.arraycopy(simpleNames, 1, nested, 0, nested.length);
        return ClassName.get(packageName, outer, nested);
    }

}
