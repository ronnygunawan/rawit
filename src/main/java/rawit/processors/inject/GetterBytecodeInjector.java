package rawit.processors.inject;

import org.objectweb.asm.*;
import org.objectweb.asm.util.CheckClassAdapter;
import rawit.processors.model.AnnotatedField;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Injects getter methods into the original {@code .class} file using ASM.
 *
 * <p>For each {@link AnnotatedField} in the provided list, the injector:
 * <ol>
 *   <li>Checks idempotency — if the class already has a method with the getter name and
 *       {@code ()} descriptor, the injection for that field is skipped.</li>
 *   <li>Adds the getter method via {@link ClassWriter#visitMethod}.</li>
 *   <li>Writes the modified bytecode back to the {@code .class} file.</li>
 * </ol>
 *
 * <p>On verification failure, the original {@code .class} file is preserved and an
 * {@code ERROR} diagnostic is emitted.
 */
public class GetterBytecodeInjector {

    /** Creates a new {@code GetterBytecodeInjector}. */
    public GetterBytecodeInjector() {}

    /**
     * Injects getter methods for all provided annotated fields into the {@code .class} file
     * at the given path.
     *
     * @param classFilePath path to the {@code .class} file to modify
     * @param fields        the annotated fields describing the getters to inject
     * @param env           the processing environment (used for diagnostics)
     */
    public void inject(final Path classFilePath, final List<AnnotatedField> fields,
                       final ProcessingEnvironment env) {
        if (fields.isEmpty()) return;

        final Messager messager = env.getMessager();
        final byte[] originalBytes;
        try {
            originalBytes = Files.readAllBytes(classFilePath);
        } catch (final IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "GetterBytecodeInjector: cannot read .class file: " + classFilePath + " — " + e.getMessage());
            return;
        }

        try {
            final ClassReader reader = new ClassReader(originalBytes);
            final ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            final GetterInjectionClassVisitor visitor = new GetterInjectionClassVisitor(writer, fields);
            reader.accept(visitor, ClassReader.SKIP_FRAMES);

            final byte[] modifiedBytes = writer.toByteArray();

            // Verify the bytecode using ASM's CheckClassAdapter before writing.
            // If verification fails, preserve the original .class and emit an ERROR.
            final java.io.PrintWriter verifyOutput = new java.io.PrintWriter(new java.io.StringWriter());
            try {
                CheckClassAdapter.verify(new ClassReader(modifiedBytes), false, verifyOutput);
            } catch (final Exception e) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "GetterBytecodeInjector: bytecode verification failed for " + classFilePath
                                + " — " + e.getMessage() + ". Original .class preserved.");
                return;
            }

            Files.write(classFilePath, modifiedBytes);
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "GetterBytecodeInjector: injected getters into " + classFilePath);

        } catch (final IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "GetterBytecodeInjector: cannot write .class file: " + classFilePath
                            + " — " + e.getMessage());
        } catch (final Exception e) {
            // Unexpected error — original .class is still on disk
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "GetterBytecodeInjector: unexpected error injecting into " + classFilePath
                            + " — " + e.getMessage() + ". Original .class preserved.");
        }
    }

    // =========================================================================
    // Return opcode resolution
    // =========================================================================

    /**
     * Selects the appropriate return opcode for the given JVM type descriptor.
     *
     * @param typeDescriptor JVM type descriptor (e.g. {@code "I"}, {@code "Ljava/lang/String;"})
     * @return the ASM return opcode
     */
    static int returnOpcodeFor(final String typeDescriptor) {
        if (typeDescriptor.isEmpty()) {
            throw new IllegalArgumentException("Empty type descriptor");
        }
        return switch (typeDescriptor.charAt(0)) {
            case 'Z', 'B', 'C', 'S', 'I' -> Opcodes.IRETURN;
            case 'J' -> Opcodes.LRETURN;
            case 'F' -> Opcodes.FRETURN;
            case 'D' -> Opcodes.DRETURN;
            case 'L', '[' -> Opcodes.ARETURN;
            default -> throw new IllegalArgumentException(
                    "Unknown type descriptor: " + typeDescriptor);
        };
    }

    // =========================================================================
    // GetterInjectionClassVisitor
    // =========================================================================

    /**
     * ASM {@link ClassVisitor} that passes through all existing members and injects
     * getter methods in {@link #visitEnd()}.
     */
    private static final class GetterInjectionClassVisitor extends ClassVisitor {

        private final List<AnnotatedField> fields;
        /** Set of "name()" descriptors already present in the class. */
        private final Set<String> existingZeroParamMethods = new HashSet<>();
        private String className; // internal name of the class being visited

        GetterInjectionClassVisitor(final ClassVisitor cv, final List<AnnotatedField> fields) {
            super(Opcodes.ASM9, cv);
            this.fields = fields;
        }

        @Override
        public void visit(final int version, final int access, final String name,
                          final String signature, final String superName,
                          final String[] interfaces) {
            this.className = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(final int access, final String name,
                                         final String descriptor, final String signature,
                                         final String[] exceptions) {
            // Track zero-parameter methods for idempotency check
            if (descriptor.startsWith("()")) {
                existingZeroParamMethods.add(name);
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }

        @Override
        public void visitEnd() {
            // Inject getter methods for each field
            for (final AnnotatedField field : fields) {
                final String getterName = field.getterName();
                if (existingZeroParamMethods.contains(getterName)) {
                    // Already present — idempotency: skip
                    continue;
                }
                injectGetter(field);
                existingZeroParamMethods.add(getterName); // prevent double-injection within same pass
            }
            super.visitEnd();
        }

        // -------------------------------------------------------------------------
        // Injection logic
        // -------------------------------------------------------------------------

        private void injectGetter(final AnnotatedField field) {
            // Determine access flags
            int accessFlags = Opcodes.ACC_PUBLIC;
            if (field.isStatic()) {
                accessFlags |= Opcodes.ACC_STATIC;
            }

            // Build method descriptor: () + field type descriptor
            final String methodDescriptor = "()" + field.fieldTypeDescriptor();

            // Build method signature for generic type preservation (nullable)
            final String methodSignature = field.fieldTypeSignature() != null
                    ? "()" + field.fieldTypeSignature()
                    : null;

            final MethodVisitor mv = cv.visitMethod(
                    accessFlags, field.getterName(), methodDescriptor, methodSignature, null);
            if (mv == null) return;

            // Mark with @GeneratedGetter so cross-compilation collision detection works
            mv.visitAnnotation("Lrawit/processors/inject/GeneratedGetter;", false).visitEnd();

            mv.visitCode();

            if (field.isStatic()) {
                // Static getter: GETSTATIC + return
                mv.visitFieldInsn(Opcodes.GETSTATIC, field.enclosingClassName(),
                        field.fieldName(), field.fieldTypeDescriptor());
            } else {
                // Instance getter: ALOAD 0 + GETFIELD + return
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, field.enclosingClassName(),
                        field.fieldName(), field.fieldTypeDescriptor());
            }

            mv.visitInsn(returnOpcodeFor(field.fieldTypeDescriptor()));

            // maxStack/maxLocals are computed by COMPUTE_FRAMES | COMPUTE_MAXS,
            // but we still need to call visitMaxs with dummy values.
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
    }
}
