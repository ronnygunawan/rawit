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
 * <p>For each tree, delegates to {@link CallerClassSpec} to build the {@code TypeSpec} for the
 * Caller_Class (which already contains nested stage interfaces and terminal interfaces), then
 * writes the result via {@code JavaFile.builder(...).build().writeTo(env.getFiler())}.
 *
 * <p>If the {@link Filer} throws a {@link javax.annotation.processing.FilerException} (file
 * already exists), the exception is caught and logged as {@code NOTE} — this is the idempotency
 * guard for generated sources on incremental builds.
 */
public class JavaPoetGenerator {

    private final Messager messager;

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
        final TypeSpec callerClass = new CallerClassSpec(tree).build();
        final String packageName = resolvePackageName(tree.group().enclosingClassName());
        final String enclosingSimple = resolveSimpleName(tree.group().enclosingClassName());

        // The Caller_Class is a nested class of the enclosing class.
        // We write it as a top-level class in a sub-package or as a nested class.
        // Standard practice: write as a member type of the enclosing class by using
        // JavaFile with the enclosing class name as the type name.
        // However, since we can't modify the original source, we write the Caller_Class
        // as a standalone top-level class in the same package, named <Enclosing>$<Caller>.
        // The bytecode injector will handle the InnerClasses attribute.
        // For now, write as a separate top-level class.
        final String callerFqn = enclosingSimple + "$" + callerClass.name;
        final TypeSpec topLevel = TypeSpec.classBuilder(callerFqn)
                .addModifiers(javax.lang.model.element.Modifier.PUBLIC)
                .addType(callerClass)
                .build();

        // Actually, the correct approach per the design is to write the Caller_Class
        // as a nested class inside the enclosing class's generated source.
        // We write a JavaFile whose type is the Caller_Class directly, using the
        // enclosing class as the "enclosing element" hint via the Filer.
        // The simplest correct approach: write the Caller_Class as a standalone file
        // named <EnclosingClass>.<CallerClass> in the same package.
        final JavaFile javaFile = JavaFile.builder(packageName, callerClass)
                .skipJavaLangImports(true)
                .build();

        try {
            javaFile.writeTo(filer);
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "Generated " + packageName + "." + callerClass.name
                            + " for " + tree.group().enclosingClassName());
        } catch (final javax.annotation.processing.FilerException e) {
            // File already exists — idempotency guard
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "Skipping already-generated " + callerClass.name
                            + " for " + tree.group().enclosingClassName() + ": " + e.getMessage());
        } catch (final IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "Failed to write generated source for " + callerClass.name + ": " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String resolvePackageName(final String binaryName) {
        final String dotName = binaryName.replace('/', '.');
        final int lastDot = dotName.lastIndexOf('.');
        return lastDot < 0 ? "" : dotName.substring(0, lastDot);
    }

    private static String resolveSimpleName(final String binaryName) {
        final String dotName = binaryName.replace('/', '.');
        final int lastDot = dotName.lastIndexOf('.');
        return lastDot < 0 ? dotName : dotName.substring(lastDot + 1);
    }
}
