package rawit.processors.inject;

import org.objectweb.asm.*;
import org.objectweb.asm.util.CheckClassAdapter;
import rawit.processors.model.MergeNode;
import rawit.processors.model.MergeNode.BranchingNode;
import rawit.processors.model.MergeNode.SharedNode;
import rawit.processors.model.MergeNode.TerminalNode;
import rawit.processors.model.MergeTree;

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
 * Injects parameterless overload methods into the original {@code .class} file using ASM.
 *
 * <p>For each {@link MergeTree} in the provided list, the injector:
 * <ol>
 *   <li>Checks idempotency — if the class already has a method with the overload name and zero
 *       parameters, the injection for that tree is skipped.</li>
 *   <li>Adds the parameterless overload via {@link ClassWriter#visitMethod}.</li>
 *   <li>Writes the modified bytecode back to the {@code .class} file.</li>
 * </ol>
 *
 * <p>On {@link VerifyError}, the original {@code .class} file is preserved and an
 * {@code ERROR} diagnostic is emitted.
 */
public class BytecodeInjector {

    /** Creates a new {@code BytecodeInjector}. */
    public BytecodeInjector() {}

    /**
     * Injects parameterless overloads for all provided merge trees into the {@code .class} file
     * at the given path.
     *
     * @param classFilePath path to the {@code .class} file to modify
     * @param trees         the merge trees describing the overloads to inject
     * @param env           the processing environment (used for diagnostics)
     */
    public void inject(final Path classFilePath, final List<MergeTree> trees,
                       final ProcessingEnvironment env) {
        if (trees.isEmpty()) return;

        final Messager messager = env.getMessager();
        final byte[] originalBytes;
        try {
            originalBytes = Files.readAllBytes(classFilePath);
        } catch (final IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "BytecodeInjector: cannot read .class file: " + classFilePath + " — " + e.getMessage());
            return;
        }

        try {
            final ClassReader reader = new ClassReader(originalBytes);
            final ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            final InjectionClassVisitor visitor = new InjectionClassVisitor(writer, trees);
            reader.accept(visitor, ClassReader.SKIP_FRAMES);

            final byte[] modifiedBytes = writer.toByteArray();

            // Verify the bytecode using ASM's CheckClassAdapter before writing.
            // If verification fails, preserve the original .class and emit an ERROR.
            final java.io.PrintWriter verifyOutput = new java.io.PrintWriter(new java.io.StringWriter());
            try {
                CheckClassAdapter.verify(new ClassReader(modifiedBytes), false, verifyOutput);
            } catch (final Exception e) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "BytecodeInjector: bytecode verification failed for " + classFilePath
                                + " — " + e.getMessage() + ". Original .class preserved.");
                return;
            }

            Files.write(classFilePath, modifiedBytes);
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "BytecodeInjector: injected overloads into " + classFilePath);

        } catch (final IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "BytecodeInjector: cannot write .class file: " + classFilePath
                            + " — " + e.getMessage());
        } catch (final Exception e) {
            // Unexpected error — original .class is still on disk
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "BytecodeInjector: unexpected error injecting into " + classFilePath
                            + " — " + e.getMessage() + ". Original .class preserved.");
        }
    }

    // =========================================================================
    // InjectionClassVisitor
    // =========================================================================

    /**
     * ASM {@link ClassVisitor} that passes through all existing members and injects
     * parameterless overloads in {@link #visitEnd()}.
     */
    private static final class InjectionClassVisitor extends ClassVisitor {

        private final List<MergeTree> trees;
        /** Set of "name()" descriptors already present in the class. */
        private final Set<String> existingZeroParamMethods = new HashSet<>();
        private String className; // internal name of the class being visited

        InjectionClassVisitor(final ClassVisitor cv, final List<MergeTree> trees) {
            super(Opcodes.ASM9, cv);
            this.trees = trees;
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
            // Inject parameterless overloads for each tree
            for (final MergeTree tree : trees) {
                final String overloadName = resolveOverloadName(tree);
                if (existingZeroParamMethods.contains(overloadName)) {
                    // Already present — idempotency: skip
                    continue;
                }
                injectOverload(tree, overloadName);
                existingZeroParamMethods.add(overloadName); // prevent double-injection within same pass
            }
            super.visitEnd();
        }

        // -------------------------------------------------------------------------
        // Injection logic
        // -------------------------------------------------------------------------

        private void injectOverload(final MergeTree tree, final String overloadName) {
            final boolean isConstructorAnnotationGroup = tree.group().members().stream()
                    .allMatch(m -> m.isConstructorAnnotation());
            final boolean isConstructorGroup = tree.group().members().stream()
                    .allMatch(m -> m.isConstructor());
            final boolean isStatic = !isConstructorGroup && tree.group().members().stream()
                    .anyMatch(m -> m.isStatic());

            // Determine access flags
            final int accessFlags = resolveAccessFlags(tree, isConstructorAnnotationGroup, isStatic);

            // Determine the caller class binary name
            // The return type is the caller class itself (not the first stage interface),
            // because the caller class is a top-level class and cannot implement its own
            // nested interface (cyclic inheritance). The caller class exposes the first
            // stage method directly.
            final String callerClassBinaryName = resolveCallerClassBinaryName(tree);
            final String returnDescriptor = "L" + callerClassBinaryName + ";";
            final String methodDescriptor = "()" + returnDescriptor;

            final MethodVisitor mv = cv.visitMethod(
                    accessFlags, overloadName, methodDescriptor, null, null);
            if (mv == null) return;

            mv.visitCode();

            if (isConstructorAnnotationGroup || (isConstructorGroup && !isConstructorAnnotationGroup)) {
                // @Constructor or @Invoker on constructor: public static factory() { return new CallerClass(); }
                mv.visitTypeInsn(Opcodes.NEW, callerClassBinaryName);
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, callerClassBinaryName,
                        "<init>", "()V", false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(2, 0);
            } else if (isStatic) {
                // Static @Invoker: public static bar() { return new Bar(); }
                mv.visitTypeInsn(Opcodes.NEW, callerClassBinaryName);
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, callerClassBinaryName,
                        "<init>", "()V", false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(2, 0);
            } else {
                // Instance @Invoker: public bar() { return new Bar(this); }
                mv.visitTypeInsn(Opcodes.NEW, callerClassBinaryName);
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ALOAD, 0); // load this
                final String enclosingDescriptor = "L" + className + ";";
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, callerClassBinaryName,
                        "<init>", "(" + enclosingDescriptor + ")V", false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(3, 1);
            }

            mv.visitEnd();
        }

        // -------------------------------------------------------------------------
        // Name / descriptor resolution helpers
        // -------------------------------------------------------------------------

        private static String resolveOverloadName(final MergeTree tree) {
            final boolean isConstructorAnnotationGroup = tree.group().members().stream()
                    .allMatch(m -> m.isConstructorAnnotation());
            if (isConstructorAnnotationGroup) {
                // @Constructor: the entry point is always named "constructor"
                return "constructor";
            }
            final boolean isConstructorGroup = tree.group().members().stream()
                    .allMatch(m -> m.isConstructor());
            if (isConstructorGroup) {
                // @Invoker on a constructor: the entry point is named after the class (lowercased)
                final String enclosingClassName = tree.group().enclosingClassName();
                final int lastSlash = enclosingClassName.lastIndexOf('/');
                final String simpleName = lastSlash < 0 ? enclosingClassName
                        : enclosingClassName.substring(lastSlash + 1);
                return simpleName.toLowerCase();
            }
            return tree.group().groupName();
        }

        private static int resolveAccessFlags(final MergeTree tree,
                                               final boolean isConstructorAnnotationGroup,
                                               final boolean isStatic) {
            final boolean isConstructorGroup = tree.group().members().stream()
                    .allMatch(m -> m.isConstructor());
            if (isConstructorAnnotationGroup || isConstructorGroup) {
                // @Constructor or @Invoker on constructor: always public static
                return Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
            }
            // Derive access from the representative method's stored accessFlags
            final var representative = tree.group().members().get(0);
            // Mask to keep only visibility bits (public=0x0001, protected=0x0004, package=0x0000)
            final int visibilityMask = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED;
            int flags = representative.accessFlags() & visibilityMask;
            if (isStatic) {
                flags |= Opcodes.ACC_STATIC;
            }
            return flags;
        }

        private static String resolveCallerClassBinaryName(final MergeTree tree) {
            final String enclosing = tree.group().enclosingClassName();
            final int lastSlash = enclosing.lastIndexOf('/');
            final String simpleName = lastSlash < 0 ? enclosing : enclosing.substring(lastSlash + 1);
            final String packagePrefix = packagePrefix(enclosing);

            final boolean isConstructorAnnotationGroup = tree.group().members().stream()
                    .allMatch(m -> m.isConstructorAnnotation());
            if (isConstructorAnnotationGroup) {
                return packagePrefix + simpleName + "Constructor";
            }
            final String groupName = tree.group().groupName();
            if ("<init>".equals(groupName)) {
                return packagePrefix + simpleName + "Invoker";
            }
            return packagePrefix + simpleName + toPascalCase(groupName) + "Invoker";
        }

        private static String packagePrefix(final String binaryClassName) {
            final int lastSlash = binaryClassName.lastIndexOf('/');
            if (lastSlash < 0) return "generated/";
            return binaryClassName.substring(0, lastSlash + 1) + "generated/";
        }

        private static String toPascalCase(final String name) {
            if (name == null || name.isEmpty()) return name;
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
    }
}
