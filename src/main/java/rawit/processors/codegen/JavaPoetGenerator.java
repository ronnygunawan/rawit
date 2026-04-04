package rawit.processors.codegen;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import rawit.processors.model.MergeTree;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.List;

/**
 * Orchestrates JavaPoet source generation for all {@link MergeTree}s.
 *
 * <p>For each tree, delegates to {@link InvokerClassSpec} to build the {@code TypeSpec} for the
 * Invoker_Class (which already contains nested stage interfaces and terminal interfaces), then
 * writes the result via {@code JavaFile.builder(...).build().writeTo(env.getFiler())}.
 *
 * <p>If the {@link Filer} throws a {@link javax.annotation.processing.FilerException} (file
 * already exists), the exception is caught and logged as {@code NOTE} — this is the idempotency
 * guard for generated sources on incremental builds.
 */
public class JavaPoetGenerator {

    private final Messager messager;

    /**
     * Creates a new {@code JavaPoetGenerator}.
     *
     * @param messager the compiler messager used to emit diagnostics
     */
    public JavaPoetGenerator(final Messager messager) {
        this.messager = messager;
    }

    /**
     * Generates source files for all provided merge trees.
     *
     * @param trees the merge trees to generate code for
     * @param env   the processing environment providing the {@link Filer}
     */
    public void generate(final List<MergeTree> trees, final ProcessingEnvironment env) {
        final Filer filer = env.getFiler();
        for (final MergeTree tree : trees) {
            generateTree(tree, filer);
        }
    }

    private void generateTree(final MergeTree tree, final Filer filer) {
        final TypeSpec callerClass = new InvokerClassSpec(tree).build();
        final String packageName = resolvePackageName(tree.group().enclosingClassName());

        // Write the Caller_Class as a top-level class:
        // 1. Strip the 'static' modifier (not valid for top-level classes)
        // 2. Qualify superinterface names with the class name (e.g. XStageInvoker → Add.XStageInvoker)
        //    because a top-level class cannot reference its own nested interfaces by simple name
        //    in the 'implements' clause.
        final TypeSpec callerAsTopLevel = asTopLevelClass(callerClass);

        final JavaFile javaFile = JavaFile.builder(packageName, callerAsTopLevel)
                .skipJavaLangImports(true)
                .build();

        try {
            javaFile.writeTo(filer);
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "Generated " + packageName + "." + callerAsTopLevel.name
                            + " for " + tree.group().enclosingClassName());
        } catch (final javax.annotation.processing.FilerException e) {
            // File already exists — idempotency guard
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "Skipping already-generated " + callerAsTopLevel.name
                            + " for " + tree.group().enclosingClassName() + ": " + e.getMessage());
        } catch (final IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "Failed to write generated source for " + callerAsTopLevel.name + ": " + e.getMessage());
        }
    }

    /**
     * Converts a nested-class {@link TypeSpec} (with {@code public static final} modifiers) into
     * a top-level class suitable for writing via the {@link Filer} by removing the {@code static}
     * modifier (not valid for top-level classes).
     */
    private static TypeSpec asTopLevelClass(final TypeSpec typeSpec) {
        final TypeSpec.Builder builder = typeSpec.toBuilder();
        builder.modifiers.remove(javax.lang.model.element.Modifier.STATIC);
        return builder.build();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String resolvePackageName(final String binaryName) {
        final String dotName = binaryName.replace('/', '.');
        final int lastDot = dotName.lastIndexOf('.');
        return lastDot < 0 ? "" : dotName.substring(0, lastDot);
    }
}
