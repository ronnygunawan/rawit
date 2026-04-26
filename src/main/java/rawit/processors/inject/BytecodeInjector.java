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
import java.util.Locale;
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

    // -------------------------------------------------------------------------
    // Public static helpers (shared with JavacAstInjector and tests)
    // -------------------------------------------------------------------------

    /**
     * Returns the parameterless entry-point method name for a merge tree.
     *
     * <ul>
     *   <li>{@code @Constructor} groups always use {@code "constructor"}.</li>
     *   <li>{@code @Invoker} on a constructor uses the enclosing simple class name lowercased.</li>
     *   <li>{@code @Invoker} on a method uses the method's group name.</li>
     * </ul>
     *
     * @param tree the merge tree
     * @return the entry-point method name
     */
    public static String resolveEntryPointName(final MergeTree tree) {
        final boolean isConstructorAnnotationGroup = tree.group().members().stream()
                .allMatch(m -> m.isConstructorAnnotation());
        if (isConstructorAnnotationGroup) {
            return "constructor";
        }
        final boolean isConstructorGroup = tree.group().members().stream()
                .allMatch(m -> m.isConstructor());
        if (isConstructorGroup) {
            final String enclosingClassName = tree.group().enclosingClassName();
            final int lastSlash = enclosingClassName.lastIndexOf('/');
            final String simpleName = lastSlash < 0 ? enclosingClassName
                    : enclosingClassName.substring(lastSlash + 1);
            return simpleName.toLowerCase(Locale.ROOT);
        }
        return tree.group().groupName();
    }

    /**
     * Returns the generated caller class binary name (slash-separated) for a merge tree.
     *
     * <p>Examples for enclosing class {@code com/example/Foo}:
     * <ul>
     *   <li>{@code @Constructor} → {@code com/example/generated/FooConstructor}</li>
     *   <li>{@code @Invoker} on constructor → {@code com/example/generated/FooInvoker}</li>
     *   <li>{@code @Invoker} on method {@code bar} → {@code com/example/generated/FooBarInvoker}</li>
     * </ul>
     *
     * @param tree the merge tree
     * @return the binary class name with {@code /} separators
     */
    public static String resolveCallerClassBinaryName(final MergeTree tree) {
        final String enclosing = tree.group().enclosingClassName();
        final int lastSlash = enclosing.lastIndexOf('/');
        final String simpleName = lastSlash < 0 ? enclosing : enclosing.substring(lastSlash + 1);
        final String packagePrefix = resolvePackagePrefix(enclosing);

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

    /**
     * Returns {@code true} when the entry-point method for the given tree is an instance
     * method (i.e. it needs a {@code this} / {@code instance} parameter to the caller
     * constructor). This is the case for non-static {@code @Invoker} on a regular method.
     *
     * @param tree the merge tree
     * @return {@code true} for instance-method entry-points
     */
    public static boolean isInstanceEntryPoint(final MergeTree tree) {
        final boolean isConstructorAnnotationGroup = tree.group().members().stream()
                .allMatch(m -> m.isConstructorAnnotation());
        if (isConstructorAnnotationGroup) return false;
        final boolean isConstructorGroup = tree.group().members().stream()
                .allMatch(m -> m.isConstructor());
        if (isConstructorGroup) return false;
        return tree.group().members().stream().noneMatch(m -> m.isStatic());
    }

    /**
     * Returns the access flags (as a long suitable for javac's {@code JCModifiers}) for the
     * entry-point method generated for the given merge tree.
     *
     * <p>This matches the flags used by {@link InjectionClassVisitor#resolveAccessFlags} so
     * that both the bytecode-injection path and the AST-injection path produce identical
     * visibility / staticness on the entry-point.
     *
     * <ul>
     *   <li>{@code @Constructor} groups and {@code @Invoker} on constructors → {@code public static}</li>
     *   <li>Static {@code @Invoker} → original visibility + {@code static}</li>
     *   <li>Instance {@code @Invoker} → original visibility (no static)</li>
     * </ul>
     *
     * @param tree the merge tree
     * @return access flags compatible with both ASM {@code Opcodes} and javac {@code Flags}
     */
    public static long resolveEntryPointAccessFlags(final MergeTree tree) {
        final boolean isConstructorAnnotationGroup = tree.group().members().stream()
                .allMatch(m -> m.isConstructorAnnotation());
        final boolean isConstructorGroup = tree.group().members().stream()
                .allMatch(m -> m.isConstructor());
        if (isConstructorAnnotationGroup || isConstructorGroup) {
            return Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC; // 0x0001 | 0x0008
        }
        final boolean isStatic = tree.group().members().stream().anyMatch(m -> m.isStatic());
        final var representative = tree.group().members().get(0);
        final int visibilityMask = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED;
        long flags = representative.accessFlags() & visibilityMask;
        if (isStatic) {
            flags |= Opcodes.ACC_STATIC;
        }
        return flags;
    }

    private static String resolvePackagePrefix(final String binaryClassName) {
        final int lastSlash = binaryClassName.lastIndexOf('/');
        if (lastSlash < 0) return "";
        return binaryClassName.substring(0, lastSlash + 1) + "generated/";
    }

    private static String toPascalCase(final String name) {
        if (name == null || name.isEmpty()) return name;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
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
        // Name / descriptor resolution helpers (delegate to outer class statics)
        // -------------------------------------------------------------------------

        private static String resolveOverloadName(final MergeTree tree) {
            return BytecodeInjector.resolveEntryPointName(tree);
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
            return BytecodeInjector.resolveCallerClassBinaryName(tree);
        }
    }
}
