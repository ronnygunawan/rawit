package rawit.processors.inject;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Injects source-visible entry-point methods into annotated class ASTs using javac's
 * internal {@code TreeMaker} API, accessed via reflection. This provides Lombok-like
 * IDE visibility for the generated staged API entry-points directly on the original class.
 *
 * <p>The injected methods mirror those that {@link BytecodeInjector} writes into the
 * {@code .class} file, so they appear on the original class (e.g. {@code Foo.constructor()},
 * {@code foo.bar()}) rather than on a separate generated class. Being part of the source AST,
 * they are visible to the javac type-checker, and thus to javac-based IDE tools such as
 * IntelliJ IDEA.
 *
 * <p>When not running under javac (e.g. ECJ / JDT LS), {@link #tryCreate} returns
 * {@code null} and all inject calls silently no-op, falling back to the existing
 * bytecode-injection path.
 */
public class JavacAstInjector {

    /**
     * Entry-point description: captures all information needed to inject a single
     * parameterless (or instance-taking) overload into an original class AST.
     *
     * @param classElement   the annotated type element (the original class)
     * @param methodName     entry-point name (e.g. {@code "constructor"}, {@code "bar"})
     * @param callerFqn      fully-qualified name of the generated caller class,
     *                       dot-separated (e.g. {@code "com.example.generated.FooBarInvoker"})
     * @param instanceMethod {@code true} if the method takes the enclosing class instance
     *                       as its sole parameter (non-static {@code @Invoker})
     * @param enclosingFqn   FQN of the original class — only required when
     *                       {@code instanceMethod} is {@code true}
     */
    public record EntryPoint(
            TypeElement classElement,
            String methodName,
            String callerFqn,
            boolean instanceMethod,
            String enclosingFqn,
            long accessFlags) {}

    // -------------------------------------------------------------------------
    // Reflection handles — all typed as Object / Method / Field to avoid
    // compile-time dependencies on com.sun.tools.javac.* internal APIs
    // -------------------------------------------------------------------------

    private final Object treeMaker;
    private final Object names;
    private final Trees trees;

    private final Method tmModifiers;
    private final Method tmIdent;
    private final Method tmSelect;
    private final Method tmVarDef;
    private final Method tmBlock;
    private final Method tmReturn;
    private final Method tmNewClass;
    private final Method tmMethodDef;
    private final Method nmFromString;
    private final Field jcClassDeclDefs;
    private final Class<?> jcClassDeclClass;
    private final Class<?> jcMethodDeclClass;
    private final Object listNil;
    private final Method listAppend;
    private final Method listOf1;
    private final Field jcMethodDeclName;
    private final Field jcMethodDeclParams;
    private final Field listHead;
    private final Field listTail;

    private JavacAstInjector(
            Object treeMaker, Object names, Trees trees,
            Method tmModifiers, Method tmIdent, Method tmSelect, Method tmVarDef,
            Method tmBlock, Method tmReturn, Method tmNewClass, Method tmMethodDef,
            Method nmFromString, Field jcClassDeclDefs,
            Class<?> jcClassDeclClass, Class<?> jcMethodDeclClass,
            Object listNil, Method listAppend, Method listOf1,
            Field jcMethodDeclName, Field jcMethodDeclParams, Field listHead, Field listTail) {
        this.treeMaker = treeMaker;
        this.names = names;
        this.trees = trees;
        this.tmModifiers = tmModifiers;
        this.tmIdent = tmIdent;
        this.tmSelect = tmSelect;
        this.tmVarDef = tmVarDef;
        this.tmBlock = tmBlock;
        this.tmReturn = tmReturn;
        this.tmNewClass = tmNewClass;
        this.tmMethodDef = tmMethodDef;
        this.nmFromString = nmFromString;
        this.jcClassDeclDefs = jcClassDeclDefs;
        this.jcClassDeclClass = jcClassDeclClass;
        this.jcMethodDeclClass = jcMethodDeclClass;
        this.listNil = listNil;
        this.listAppend = listAppend;
        this.listOf1 = listOf1;
        this.jcMethodDeclName = jcMethodDeclName;
        this.jcMethodDeclParams = jcMethodDeclParams;
        this.listHead = listHead;
        this.listTail = listTail;
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Creates a {@code JavacAstInjector} for the given processing environment.
     *
     * @param env the annotation processing environment
     * @return a new injector, or {@code null} when not running under javac or when
     *         the internal API is inaccessible
     */
    public static JavacAstInjector tryCreate(final ProcessingEnvironment env) {
        try {
            // JavacTask.instance() throws IllegalArgumentException when not running under javac
            final JavacTask javacTask = JavacTask.instance(env);

            // BasicJavacTask.getContext() returns the compiler Context.
            // Use setAccessible(true) because BasicJavacTask is in a non-exported package
            // (jdk.compiler/com.sun.tools.javac.api) on Java 9+.
            final Method getContext = javacTask.getClass().getMethod("getContext");
            getContext.setAccessible(true);
            final Object context = getContext.invoke(javacTask);

            final Trees trees = Trees.instance(env);

            // Load internal compiler classes by name (avoids compile-time dependency)
            final Class<?> contextClass = context.getClass();
            final Class<?> treeMakerClass = Class.forName("com.sun.tools.javac.tree.TreeMaker");
            final Class<?> namesClass = Class.forName("com.sun.tools.javac.util.Names");
            final Class<?> nameClass = Class.forName("com.sun.tools.javac.util.Name");
            final Class<?> jcExprClass = Class.forName("com.sun.tools.javac.tree.JCTree$JCExpression");
            final Class<?> jcModifiersClass = Class.forName("com.sun.tools.javac.tree.JCTree$JCModifiers");
            final Class<?> jcVarDeclClass = Class.forName("com.sun.tools.javac.tree.JCTree$JCVariableDecl");
            final Class<?> jcBlockClass = Class.forName("com.sun.tools.javac.tree.JCTree$JCBlock");
            final Class<?> jcClassDeclClass = Class.forName("com.sun.tools.javac.tree.JCTree$JCClassDecl");
            final Class<?> jcMethodDeclClass = Class.forName("com.sun.tools.javac.tree.JCTree$JCMethodDecl");
            final Class<?> jcTreeClass = Class.forName("com.sun.tools.javac.tree.JCTree");
            final Class<?> listClass = Class.forName("com.sun.tools.javac.util.List");

            // Instantiate TreeMaker and Names via static factory methods
            final Object treeMaker = findStaticInstance(treeMakerClass, contextClass, context);
            final Object names = findStaticInstance(namesClass, contextClass, context);

            if (treeMaker == null || names == null) return null;

            // Build reflection method handles for TreeMaker
            // setAccessible(true) is required on Java 9+ without --add-opens (silently fails if missing)
            final Method tmModifiers = treeMakerClass.getMethod("Modifiers", long.class);
            tmModifiers.setAccessible(true);
            final Method tmIdent = treeMakerClass.getMethod("Ident", nameClass);
            tmIdent.setAccessible(true);
            final Method tmSelect = treeMakerClass.getMethod("Select", jcExprClass, nameClass);
            tmSelect.setAccessible(true);
            final Method tmVarDef = treeMakerClass.getMethod("VarDef",
                    jcModifiersClass, nameClass, jcExprClass, jcExprClass);
            tmVarDef.setAccessible(true);
            final Method tmBlock = treeMakerClass.getMethod("Block", long.class, listClass);
            tmBlock.setAccessible(true);
            final Method tmReturn = treeMakerClass.getMethod("Return", jcExprClass);
            tmReturn.setAccessible(true);
            // NewClass(encl, typeargs, clazz, args, def)
            final Method tmNewClass = treeMakerClass.getMethod("NewClass",
                    jcExprClass, listClass, jcExprClass, listClass, jcClassDeclClass);
            tmNewClass.setAccessible(true);
            // MethodDef(mods, name, restype, typarams, params, thrown, body, defaultValue)
            final Method tmMethodDef = treeMakerClass.getMethod("MethodDef",
                    jcModifiersClass, nameClass, jcExprClass,
                    listClass, listClass, listClass, jcBlockClass, jcExprClass);
            tmMethodDef.setAccessible(true);

            final Method nmFromString = namesClass.getMethod("fromString", String.class);
            nmFromString.setAccessible(true);

            // JCClassDecl.defs: the list of class members
            final Field jcClassDeclDefs = jcClassDeclClass.getField("defs");
            jcClassDeclDefs.setAccessible(true);

            // List utility
            final Method listNilMethod = listClass.getMethod("nil");
            listNilMethod.setAccessible(true);
            final Object listNil = listNilMethod.invoke(null);
            final Method listAppend = listClass.getMethod("append", Object.class);
            listAppend.setAccessible(true);
            final Method listOf1 = listClass.getMethod("of", Object.class);
            listOf1.setAccessible(true);

            // JCMethodDecl.name field (for idempotency check)
            final Field jcMethodDeclName = jcMethodDeclClass.getField("name");
            jcMethodDeclName.setAccessible(true);

            // JCMethodDecl.params field (for zero-param idempotency check)
            final Field jcMethodDeclParams = jcMethodDeclClass.getField("params");
            jcMethodDeclParams.setAccessible(true);

            // List.head / List.tail for iteration
            final Field listHead = listClass.getField("head");
            listHead.setAccessible(true);
            final Field listTail = listClass.getField("tail");
            listTail.setAccessible(true);

            return new JavacAstInjector(
                    treeMaker, names, trees,
                    tmModifiers, tmIdent, tmSelect, tmVarDef,
                    tmBlock, tmReturn, tmNewClass, tmMethodDef,
                    nmFromString, jcClassDeclDefs,
                    jcClassDeclClass, jcMethodDeclClass,
                    listNil, listAppend, listOf1,
                    jcMethodDeclName, jcMethodDeclParams, listHead, listTail);

        } catch (final IllegalArgumentException ignored) {
            // Not running under javac
            return null;
        } catch (final Exception ignored) {
            // Internal API inaccessible or unexpected shape — silently disable
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Injects an entry-point method into the original annotated class for source-level
     * IDE visibility.
     *
     * <p>For non-static cases ({@code @Constructor}, static {@code @Invoker}): injects a
     * {@code public static} method with no parameters.<br>
     * For instance {@code @Invoker}: injects a {@code public} (non-static) instance method
     * with no parameters, whose body calls {@code return new CallerClass(this)}.
     *
     * <p>The injection is idempotent: if a method with {@code entryPoint.methodName()} already
     * exists in the class, the call is a no-op. Any exception during injection is silently
     * swallowed — the bytecode-injection fallback still handles runtime correctness.
     *
     * @param entryPoint description of the method to inject
     */
    public void inject(final EntryPoint entryPoint) {
        try {
            final com.sun.source.tree.Tree tree = trees.getTree(entryPoint.classElement());
            if (tree == null || !jcClassDeclClass.isInstance(tree)) return;

            if (methodExists(tree, entryPoint.methodName())) return;

            // Build return-type expression: fully-qualified name via chained Select nodes
            final Object returnTypeExpr = buildFQNExpr(entryPoint.callerFqn());

            // Instance @Invoker: public CallerType bar() { return new CallerType(this); }
            // All other cases: public static CallerType method() { return new CallerType(); }
            final Object paramsList = listNil; // entry-point is always zero-argument

            // Build constructor-call argument list
            final Object newCallerTypeExpr = buildFQNExpr(entryPoint.callerFqn());
            final Object ctorArgsList;
            if (entryPoint.instanceMethod()) {
                // Pass 'this' to the caller class constructor
                final Object thisIdent = tmIdent.invoke(treeMaker,
                        nmFromString.invoke(names, "this"));
                ctorArgsList = listOf1.invoke(null, thisIdent);
            } else {
                ctorArgsList = listNil;
            }
            final Object newExpr = tmNewClass.invoke(treeMaker,
                    null,            // enclosing expression (none — top-level class)
                    listNil,         // type arguments
                    newCallerTypeExpr,
                    ctorArgsList,
                    null);           // anonymous class body

            // Wrap in: return <newExpr>;
            final Object returnStmt = tmReturn.invoke(treeMaker, newExpr);

            // Build method body block
            final Object body = tmBlock.invoke(treeMaker, 0L, listOf1.invoke(null, returnStmt));

            // Modifiers: use the access flags resolved from the original member
            final long accessFlags = entryPoint.accessFlags();
            final Object mods = tmModifiers.invoke(treeMaker, accessFlags);

            final Object methodDecl = tmMethodDef.invoke(treeMaker,
                    mods,
                    nmFromString.invoke(names, entryPoint.methodName()),
                    returnTypeExpr,
                    listNil,     // type parameters
                    paramsList,  // method parameters (always empty)
                    listNil,     // thrown types
                    body,
                    null);       // default value (not an annotation method)

            // Append to the class's member list
            final Object currentDefs = jcClassDeclDefs.get(tree);
            jcClassDeclDefs.set(tree, listAppend.invoke(currentDefs, methodDecl));

        } catch (final Exception ignored) {
            // Silently fall back — bytecode injection still provides runtime correctness
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Finds and invokes the static {@code instance(Context)} factory method on an internal
     * javac class (e.g. {@code TreeMaker.instance(context)}).
     * <p>
     * We iterate declared methods because the exact parameter type is the internal
     * {@code Context} class — doing an exact getMethod() lookup requires the class object,
     * which we may or may not have under the right classloader.
     */
    private static Object findStaticInstance(
            final Class<?> targetClass, final Class<?> contextClass, final Object context) {
        for (final Method m : targetClass.getMethods()) {
            if ("instance".equals(m.getName())
                    && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isAssignableFrom(contextClass)) {
                try {
                    return m.invoke(null, context);
                } catch (final Exception ignored) {
                    // try next overload
                }
            }
        }
        return null;
    }

    /**
     * Builds a fully-qualified name expression by chaining {@code Select} nodes.
     * <p>
     * For example {@code "com.example.generated.FooBarInvoker"} becomes
     * {@code Select(Select(Select(Ident("com"), "example"), "generated"), "FooBarInvoker")}.
     */
    private Object buildFQNExpr(final String fqn) throws Exception {
        final String[] parts = fqn.split("\\.");
        Object expr = tmIdent.invoke(treeMaker, nmFromString.invoke(names, parts[0]));
        for (int i = 1; i < parts.length; i++) {
            expr = tmSelect.invoke(treeMaker, expr, nmFromString.invoke(names, parts[i]));
        }
        return expr;
    }

    /**
     * Returns {@code true} if the given class AST already contains a <em>zero-parameter</em>
     * method with the given simple name. Used to ensure idempotent injection.
     *
     * <p>A method on the original class may have the same name as the entry-point being
     * injected (e.g. {@code multiply(int,int)} for an {@code @Invoker} named {@code multiply}).
     * Checking only the name would incorrectly suppress injection; checking for zero-parameter
     * methods avoids this false-positive.
     */
    private boolean methodExists(final Object classDecl, final String methodName) throws Exception {
        Object current = jcClassDeclDefs.get(classDecl);
        while (current != null) {
            final Object head = listHead.get(current);
            if (head != null && jcMethodDeclClass.isInstance(head)) {
                final Object name = jcMethodDeclName.get(head);
                if (methodName.equals(name.toString())) {
                    // Only treat as a hit if this is a zero-parameter method
                    final Object params = jcMethodDeclParams.get(head);
                    if (params == null || params == listNil || isEmpty(params)) return true;
                }
            }
            final Object tail = listTail.get(current);
            if (tail == null || tail == current || tail == listNil) break;
            current = tail;
        }
        return false;
    }

    /**
     * Returns {@code true} when the given {@code com.sun.tools.javac.util.List} node has
     * no elements (its {@code head} field is {@code null}). This is the canonical empty-list
     * sentinel used by javac's internal linked-list type.
     */
    private boolean isEmpty(final Object list) throws Exception {
        return listHead.get(list) == null;
    }
}
