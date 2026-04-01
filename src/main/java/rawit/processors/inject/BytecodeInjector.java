package rawit.processors.inject;

import org.objectweb.asm.*;
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

            // Verify the bytecode before writing
            try {
                new ClassReader(modifiedBytes); // basic structural check
            } catch (final Exception e) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "BytecodeInjector: bytecode verification failed for " + classFilePath
                                + " — " + e.getMessage());
                return;
            }

            Files.write(classFilePath, modifiedBytes);
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "BytecodeInjector: injected overloads into " + classFilePath);

        } catch (final VerifyError e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "BytecodeInjector: VerifyError injecting into " + classFilePath
                            + " — " + e.getMessage() + ". Original .class preserved.");
            // Original bytes are already on disk — nothing to restore
        } catch (final IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "BytecodeInjector: cannot write .class file: " + classFilePath
                            + " — " + e.getMessage());
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
            final boolean isConstructorGroup = tree.group().members().stream()
                    .allMatch(m -> m.isConstructor());
            final boolean isStatic = !isConstructorGroup && tree.group().members().stream()
                    .anyMatch(m -> m.isStatic());

            // Determine access flags
            final int accessFlags = resolveAccessFlags(tree, isConstructorGroup, isStatic);

            // Determine the caller class binary name and first stage interface binary name
            final String callerClassBinaryName = resolveCallerClassBinaryName(tree);
            final String firstStageIfaceBinaryName = resolveFirstStageInterfaceBinaryName(tree);
            final String returnDescriptor = "L" + firstStageIfaceBinaryName + ";";
            final String methodDescriptor = "()" + returnDescriptor;

            final MethodVisitor mv = cv.visitMethod(
                    accessFlags, overloadName, methodDescriptor, null, null);
            if (mv == null) return;

            mv.visitCode();

            if (isConstructorGroup) {
                // @Constructor: public static constructor() { return new Constructor(); }
                mv.visitTypeInsn(Opcodes.NEW, callerClassBinaryName);
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, callerClassBinaryName,
                        "<init>", "()V", false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(2, 0);
            } else if (isStatic) {
                // Static @Curry: public static bar() { return new Bar(); }
                mv.visitTypeInsn(Opcodes.NEW, callerClassBinaryName);
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, callerClassBinaryName,
                        "<init>", "()V", false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(2, 0);
            } else {
                // Instance @Curry: public bar() { return new Bar(this); }
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
            final boolean isConstructorGroup = tree.group().members().stream()
                    .allMatch(m -> m.isConstructor());
            if (isConstructorGroup) {
                return "constructor";
            }
            return tree.group().groupName();
        }

        private static int resolveAccessFlags(final MergeTree tree,
                                               final boolean isConstructorGroup,
                                               final boolean isStatic) {
            if (isConstructorGroup) {
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
            final boolean isConstructorGroup = tree.group().members().stream()
                    .allMatch(m -> m.isConstructor());
            if (isConstructorGroup) {
                return enclosing + "$Constructor";
            }
            final String groupName = tree.group().groupName();
            return enclosing + "$" + toPascalCase(groupName);
        }

        private static String resolveFirstStageInterfaceBinaryName(final MergeTree tree) {
            final String callerBinaryName = resolveCallerClassBinaryName(tree);
            final boolean isConstructorGroup = tree.group().members().stream()
                    .allMatch(m -> m.isConstructor());
            final String suffix = isConstructorGroup ? "StageConstructor" : "StageCaller";

            final MergeNode root = tree.root();
            final String firstStageName = resolveFirstStageInterfaceName(root, tree, suffix);
            return callerBinaryName + "$" + firstStageName;
        }

        private static String resolveFirstStageInterfaceName(final MergeNode node,
                                                               final MergeTree tree,
                                                               final String suffix) {
            return switch (node) {
                case SharedNode shared -> toPascalCase(shared.paramName()) + suffix;
                case BranchingNode branching -> toPascalCase(tree.group().groupName()) + suffix;
                case TerminalNode terminal -> {
                    if (terminal.continuation() != null) {
                        yield resolveFirstStageInterfaceName(terminal.continuation(), tree, suffix);
                    }
                    // Pure terminal at root — use terminal interface name
                    yield (suffix.contains("Constructor")) ? "ConstructStageCaller" : "InvokeStageCaller";
                }
            };
        }

        private static String toPascalCase(final String name) {
            if (name == null || name.isEmpty()) return name;
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
    }
}
