package rg.rawit.processors.model;

import java.util.List;

/**
 * Immutable model of a validated element annotated with {@code @Invoker} or {@code @Constructor}.
 *
 * <p>Captures everything needed for code generation and bytecode injection, with no dependency
 * on {@code javax.lang.model} types after construction.
 *
 * @param enclosingClassName   binary name of the enclosing class, e.g. {@code "com/example/Foo"}
 * @param methodName           simple method name, e.g. {@code "bar"} or {@code "<init>"}
 * @param isStatic             {@code true} if the method is declared {@code static}
 * @param isConstructor        {@code true} if this represents a constructor
 * @param isConstructorAnnotation {@code true} if annotated with {@code @Constructor} (as opposed
 *                             to {@code @Invoker}); only meaningful when {@code isConstructor} is
 *                             {@code true}
 * @param parameters           ordered list of parameters
 * @param returnTypeDescriptor JVM return type descriptor, e.g. {@code "I"} or {@code "V"}
 * @param checkedExceptions    binary names of declared checked exception types
 * @param accessFlags          ASM access flags (e.g. {@code Opcodes.ACC_PUBLIC}); defaults to
 *                             {@code 0x0001} (public) when using the 7-arg constructor
 */
public record AnnotatedMethod(
        String enclosingClassName,
        String methodName,
        boolean isStatic,
        boolean isConstructor,
        boolean isConstructorAnnotation,
        List<Parameter> parameters,
        String returnTypeDescriptor,
        List<String> checkedExceptions,
        int accessFlags
) {
    /** Convenience constructor that defaults {@code accessFlags} to {@code ACC_PUBLIC} (0x0001)
     *  and {@code isConstructorAnnotation} to {@code false}. */
    public AnnotatedMethod(
            String enclosingClassName,
            String methodName,
            boolean isStatic,
            boolean isConstructor,
            List<Parameter> parameters,
            String returnTypeDescriptor,
            List<String> checkedExceptions
    ) {
        this(enclosingClassName, methodName, isStatic, isConstructor, false,
                parameters, returnTypeDescriptor, checkedExceptions, 0x0001 /* ACC_PUBLIC */);
    }

    /** Convenience constructor that defaults {@code accessFlags} to {@code ACC_PUBLIC} (0x0001). */
    public AnnotatedMethod(
            String enclosingClassName,
            String methodName,
            boolean isStatic,
            boolean isConstructor,
            List<Parameter> parameters,
            String returnTypeDescriptor,
            List<String> checkedExceptions,
            int accessFlags
    ) {
        this(enclosingClassName, methodName, isStatic, isConstructor, false,
                parameters, returnTypeDescriptor, checkedExceptions, accessFlags);
    }

    public AnnotatedMethod {
        parameters = List.copyOf(parameters);
        checkedExceptions = List.copyOf(checkedExceptions);
    }
}
