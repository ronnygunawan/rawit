package rawit.processors.ast;

import com.sun.source.util.Trees;
import rawit.processors.model.AnnotatedField;
import rawit.processors.model.MergeTree;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Adds the parameterless entry-point method declarations into the class's
 * javac AST ({@code JCTree.JCClassDecl}) so that IntelliSense can resolve
 * them from source-level analysis alone — without waiting for a full
 * recompile and bytecode-injection pass.
 *
 * <h3>How it works</h3>
 * <p>The standard Java annotation processing API provides no public way to add
 * new members to an existing class.  Rawit therefore uses the same technique
 * as <a href="https://projectlombok.org/">Lombok</a>: it accesses the
 * {@code com.sun.tools.javac.*} internal APIs via reflection to obtain the live
 * {@code JCClassDecl} AST node and prepend a {@code JCMethodDecl} to its
 * {@code defs} list.
 *
 * <h3>Graceful fallback</h3>
 * <p>Every step that touches javac internals is wrapped in a try/catch.
 * {@link #tryCreate(ProcessingEnvironment)} returns {@code null} when not
 * running under javac (e.g. ECJ) or when any reflection step fails. The
 * injection methods themselves also swallow all exceptions silently: the
 * existing bytecode-injection path is the authoritative mechanism for
 * correctness; this class is a best-effort IDE-assistance layer.
 *
 * <h3>Idempotency</h3>
 * <p>Before prepending a method, the injector checks whether the class's
 * {@code defs} already contains a zero-parameter method with the same name.
 * This prevents duplicate declarations during multi-round compilation.
 */
public final class AstEntryPointInjector {

    // -------------------------------------------------------------------------
    // Access-flag constants (Flags.java in com.sun.tools.javac.code)
    // -------------------------------------------------------------------------

    private static final long ACC_PUBLIC = 0x1L;
    private static final long ACC_STATIC = 0x8L;

    // -------------------------------------------------------------------------
    // Javac-internal references (obtained via reflection in tryCreate)
    // -------------------------------------------------------------------------

    private final Trees trees;
    private final Object treeMaker;       // com.sun.tools.javac.tree.TreeMaker
    private final Object names;           // com.sun.tools.javac.util.Names
    private final Field classDefsField;   // JCClassDecl.defs (public)
    private final Field treePosField;     // JCTree.pos (public int)
    private final Object thisName;        // Names._this  (the Name for "this")

    // TreeMaker factory methods
    private final Method tmAt;            // TreeMaker.at(int pos)
    private final Method tmModifiers;     // TreeMaker.Modifiers(long flags)
    private final Method tmIdent;         // TreeMaker.Ident(Name name)
    private final Method tmSelect;        // TreeMaker.Select(JCExpression, Name)
    private final Method tmNewClass;      // TreeMaker.NewClass(encl, typeargs, clazz, args, def)
    private final Method tmReturn;        // TreeMaker.Return(JCExpression)
    private final Method tmBlock;         // TreeMaker.Block(long flags, List<JCStatement>)
    private final Method tmMethodDef;     // TreeMaker.MethodDef(mods, name, restype, …)
    private final Method tmTypeIdent;     // TreeMaker.TypeIdent(TypeTag)
    private final Method tmTypeArray;     // TreeMaker.TypeArray(JCExpression)

    // Names factory
    private final Method fromString;      // Names.fromString(String)

    // com.sun.tools.javac.util.List factory and mutator
    private final Method listNil;         // List.nil()
    private final Method listOf;          // List.of(E x1)
    private final Method listPrepend;     // list.prepend(E x)

    // TypeTag enum constants (looked up lazily)
    private final Class<?> typeTagClass;  // com.sun.tools.javac.code.TypeTag

    // JCMethodDecl introspection (for idempotency check)
    private final Class<?> jcMethodDeclClass; // com.sun.tools.javac.tree.JCTree$JCMethodDecl
    private final Field jcMethodNameField;    // JCMethodDecl.name
    private final Field jcMethodParamsField;  // JCMethodDecl.params

    // -------------------------------------------------------------------------
    // Constructor (private — use tryCreate)
    // -------------------------------------------------------------------------

    private AstEntryPointInjector(
            Trees trees, Object treeMaker, Object names,
            Field classDefsField, Field treePosField, Object thisName,
            Method tmAt, Method tmModifiers, Method tmIdent, Method tmSelect,
            Method tmNewClass, Method tmReturn, Method tmBlock, Method tmMethodDef,
            Method tmTypeIdent, Method tmTypeArray,
            Method fromString, Method listNil, Method listOf, Method listPrepend,
            Class<?> typeTagClass,
            Class<?> jcMethodDeclClass, Field jcMethodNameField, Field jcMethodParamsField) {
        this.trees = trees;
        this.treeMaker = treeMaker;
        this.names = names;
        this.classDefsField = classDefsField;
        this.treePosField = treePosField;
        this.thisName = thisName;
        this.tmAt = tmAt;
        this.tmModifiers = tmModifiers;
        this.tmIdent = tmIdent;
        this.tmSelect = tmSelect;
        this.tmNewClass = tmNewClass;
        this.tmReturn = tmReturn;
        this.tmBlock = tmBlock;
        this.tmMethodDef = tmMethodDef;
        this.tmTypeIdent = tmTypeIdent;
        this.tmTypeArray = tmTypeArray;
        this.fromString = fromString;
        this.listNil = listNil;
        this.listOf = listOf;
        this.listPrepend = listPrepend;
        this.typeTagClass = typeTagClass;
        this.jcMethodDeclClass = jcMethodDeclClass;
        this.jcMethodNameField = jcMethodNameField;
        this.jcMethodParamsField = jcMethodParamsField;
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Attempts to create an {@code AstEntryPointInjector} by reflectively accessing
     * javac internals from the given {@link ProcessingEnvironment}.
     *
     * @param env the annotation processing environment
     * @return a ready-to-use injector, or {@code null} if the environment is not
     *         javac or any required reflection step fails
     */
    public static AstEntryPointInjector tryCreate(final ProcessingEnvironment env) {
        try {
            final Trees trees = Trees.instance(env);

            // Obtain the javac Context from JavacProcessingEnvironment
            final Object context = resolveContext(env);
            if (context == null) return null;

            final Class<?> contextClass = resolveContextClass(context);
            if (contextClass == null) return null;

            // --- TreeMaker ---
            final Class<?> treeMakerClass =
                    Class.forName("com.sun.tools.javac.tree.TreeMaker");
            final Method tmInstance = treeMakerClass.getMethod("instance", contextClass);
            final Object treeMaker = tmInstance.invoke(null, context);

            // --- Names ---
            final Class<?> namesClass =
                    Class.forName("com.sun.tools.javac.util.Names");
            final Method namesInstance = namesClass.getMethod("instance", contextClass);
            final Object names = namesInstance.invoke(null, context);

            // Names._this  (the Name for the `this` keyword)
            final Field thisNameField = namesClass.getDeclaredField("_this");
            thisNameField.setAccessible(true);
            final Object thisName = thisNameField.get(names);

            // Names.fromString(String)
            final Method fromString = namesClass.getMethod("fromString", String.class);

            // --- JCTree.pos and JCClassDecl.defs ---
            final Class<?> jcTreeClass =
                    Class.forName("com.sun.tools.javac.tree.JCTree");
            final Field treePosField = jcTreeClass.getField("pos");

            final Class<?> jcClassDeclClass =
                    Class.forName("com.sun.tools.javac.tree.JCTree$JCClassDecl");
            final Field classDefsField = jcClassDeclClass.getField("defs");

            // --- JCMethodDecl introspection (for idempotency check) ---
            final Class<?> jcMethodDeclClass =
                    Class.forName("com.sun.tools.javac.tree.JCTree$JCMethodDecl");
            final Field jcMethodNameField = jcMethodDeclClass.getField("name");
            final Field jcMethodParamsField = jcMethodDeclClass.getField("params");

            // --- com.sun.tools.javac.util.List ---
            final Class<?> listClass =
                    Class.forName("com.sun.tools.javac.util.List");
            final Method listNil = listClass.getMethod("nil");
            final Method listOf = listClass.getMethod("of", Object.class);
            final Method listPrepend = listClass.getMethod("prepend", Object.class);

            // --- TypeTag (for primitive types) ---
            final Class<?> typeTagClass =
                    Class.forName("com.sun.tools.javac.code.TypeTag");

            // --- TreeMaker methods ---
            final Method tmAt       = findMethod(treeMakerClass, "at", 1);
            final Method tmModifiers = findMethod(treeMakerClass, "Modifiers", 1);
            final Class<?> nameClass = Class.forName("com.sun.tools.javac.util.Name");
            final Method tmIdent    = findMethodByFirstParamType(treeMakerClass, "Ident", nameClass);
            final Method tmSelect   = findMethod(treeMakerClass, "Select", 2);
            final Method tmNewClass = findMethod(treeMakerClass, "NewClass", 5);
            final Method tmReturn   = findMethod(treeMakerClass, "Return", 1);
            final Method tmBlock    = findMethod(treeMakerClass, "Block", 2);
            final Method tmMethodDef = findMethod(treeMakerClass, "MethodDef", 9);
            final Method tmTypeIdent = findMethodByFirstParamType(
                    treeMakerClass, "TypeIdent", typeTagClass);
            final Method tmTypeArray = findMethod(treeMakerClass, "TypeArray", 1);

            if (hasNull(tmAt, tmModifiers, tmIdent, tmSelect, tmNewClass,
                    tmReturn, tmBlock, tmMethodDef)) {
                return null;
            }
            // tmTypeIdent and tmTypeArray are optional: they are only used by
            // buildTypeFromDescriptor() for getter return-type expressions. If either
            // is null (unlikely on standard JDKs), the getter AST injection silently
            // skips primitive and array types while still injecting for reference types.

            return new AstEntryPointInjector(
                    trees, treeMaker, names,
                    classDefsField, treePosField, thisName,
                    tmAt, tmModifiers, tmIdent, tmSelect,
                    tmNewClass, tmReturn, tmBlock, tmMethodDef,
                    tmTypeIdent, tmTypeArray,
                    fromString, listNil, listOf, listPrepend,
                    typeTagClass,
                    jcMethodDeclClass, jcMethodNameField, jcMethodParamsField);

        } catch (final Exception ignored) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Public injection methods
    // -------------------------------------------------------------------------

    /**
     * Adds the parameterless entry-point method for the given {@link MergeTree} to
     * the {@link TypeElement}'s javac AST.
     *
     * <p>For an instance {@code @Invoker} method named {@code add} on class
     * {@code Calculator} this adds:
     * <pre>{@code
     * public com.example.model.generated.CalculatorAddInvoker add() {
     *     return new com.example.model.generated.CalculatorAddInvoker(this);
     * }
     * }</pre>
     *
     * @param typeElement the class receiving the entry-point method
     * @param tree        the merge tree describing the overload group
     */
    public void injectInvokerEntryPoint(final TypeElement typeElement, final MergeTree tree) {
        try {
            final Object classDecl = trees.getTree(typeElement);
            if (classDecl == null) return;

            final boolean isConstructorAnnot = tree.group().members().stream()
                    .allMatch(m -> m.isConstructorAnnotation());
            final boolean isConstructorGroup = tree.group().members().stream()
                    .allMatch(m -> m.isConstructor());
            final boolean isStatic = isConstructorAnnot || isConstructorGroup
                    || tree.group().members().stream().anyMatch(m -> m.isStatic());
            final boolean needsInstance = !isStatic;

            final String methodName = resolveEntryPointName(
                    tree, isConstructorAnnot, isConstructorGroup);
            final String callerFqn = resolveCallerFqn(tree, isConstructorAnnot);

            if (hasZeroArgMethod(classDecl, methodName)) return;

            final int pos = (int) treePosField.get(classDecl);
            tmAt.invoke(treeMaker, pos);

            final Object methodDecl = buildInvokerEntryPointMethod(
                    methodName, isStatic, callerFqn, needsInstance);
            if (methodDecl == null) return;

            prependToDefs(classDecl, methodDecl);
        } catch (final Exception ignored) {
            // Fall back silently — bytecode injection is the authoritative path
        }
    }

    /**
     * Adds the getter method for the given {@link AnnotatedField} to the
     * {@link TypeElement}'s javac AST.
     *
     * <p>For a field {@code String name} on class {@code User} this adds:
     * <pre>{@code
     * public java.lang.String getName() { return this.name; }
     * }</pre>
     *
     * @param typeElement the class receiving the getter method
     * @param field       the annotated field describing the getter to inject
     */
    public void injectGetterMethod(final TypeElement typeElement, final AnnotatedField field) {
        try {
            final Object classDecl = trees.getTree(typeElement);
            if (classDecl == null) return;

            if (hasZeroArgMethod(classDecl, field.getterName())) return;

            final int pos = (int) treePosField.get(classDecl);
            tmAt.invoke(treeMaker, pos);

            final Object returnTypeExpr = buildTypeFromDescriptor(field.fieldTypeDescriptor());
            if (returnTypeExpr == null) return;

            final Object fieldAccessExpr;
            if (field.isStatic()) {
                // return EnclosingClass.fieldName;
                final String enclosingFqn = field.enclosingClassName().replace('/', '.');
                final Object classExpr = buildQualifiedName(enclosingFqn);
                fieldAccessExpr = tmSelect.invoke(treeMaker, classExpr,
                        fromString.invoke(names, field.fieldName()));
            } else {
                // return this.fieldName;
                final Object thisIdent = tmIdent.invoke(treeMaker, thisName);
                fieldAccessExpr = tmSelect.invoke(treeMaker, thisIdent,
                        fromString.invoke(names, field.fieldName()));
            }

            final Object returnStmt = tmReturn.invoke(treeMaker, fieldAccessExpr);
            final Object stmtList = listOf.invoke(null, returnStmt);
            final Object body = tmBlock.invoke(treeMaker, 0L, stmtList);

            final long flags = ACC_PUBLIC | (field.isStatic() ? ACC_STATIC : 0L);
            final Object modifiers = tmModifiers.invoke(treeMaker, flags);
            final Object methodName = fromString.invoke(names, field.getterName());
            final Object emptyList = listNil.invoke(null);

            final Object methodDecl = tmMethodDef.invoke(treeMaker,
                    modifiers, methodName, returnTypeExpr,
                    emptyList, null, emptyList, emptyList, body, null);
            if (methodDecl == null) return;

            prependToDefs(classDecl, methodDecl);
        } catch (final Exception ignored) {
            // Fall back silently
        }
    }

    // -------------------------------------------------------------------------
    // AST building helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the entry-point method declaration for an {@code @Invoker} or
     * {@code @Constructor} group.
     */
    private Object buildInvokerEntryPointMethod(
            final String methodName,
            final boolean isStatic,
            final String callerFqn,
            final boolean needsInstance) throws Exception {

        final Object returnTypeExpr = buildQualifiedName(callerFqn);

        final Object args;
        if (needsInstance) {
            final Object thisIdent = tmIdent.invoke(treeMaker, thisName);
            args = listOf.invoke(null, thisIdent);
        } else {
            args = listNil.invoke(null);
        }

        final Object emptyList = listNil.invoke(null);
        final Object newExpr = tmNewClass.invoke(treeMaker,
                null, emptyList, buildQualifiedName(callerFqn), args, null);
        final Object returnStmt = tmReturn.invoke(treeMaker, newExpr);
        final Object stmtList = listOf.invoke(null, returnStmt);
        final Object body = tmBlock.invoke(treeMaker, 0L, stmtList);

        final long flags = ACC_PUBLIC | (isStatic ? ACC_STATIC : 0L);
        final Object modifiers = tmModifiers.invoke(treeMaker, flags);
        final Object nameObj = fromString.invoke(names, methodName);
        final Object emptyList2 = listNil.invoke(null);

        return tmMethodDef.invoke(treeMaker,
                modifiers, nameObj, returnTypeExpr,
                emptyList2, null, emptyList2, emptyList2, body, null);
    }

    /**
     * Builds a {@code JCExpression} for a dot-separated fully-qualified name.
     * <p>E.g. {@code "com.example.Foo"} →
     * {@code Select(Select(Ident("com"), "example"), "Foo")}.
     */
    private Object buildQualifiedName(final String fqn) throws Exception {
        final String[] parts = fqn.split("\\.");
        Object expr = tmIdent.invoke(treeMaker, fromString.invoke(names, parts[0]));
        for (int i = 1; i < parts.length; i++) {
            expr = tmSelect.invoke(treeMaker, expr, fromString.invoke(names, parts[i]));
        }
        return expr;
    }

    /**
     * Converts a JVM type descriptor to a javac {@code JCExpression} type node.
     * Returns {@code null} for unsupported descriptors (e.g. void).
     */
    private Object buildTypeFromDescriptor(final String desc) throws Exception {
        if (desc == null || desc.isEmpty()) return null;
        return switch (desc.charAt(0)) {
            case 'Z' -> primitiveType("BOOLEAN");
            case 'B' -> primitiveType("BYTE");
            case 'C' -> primitiveType("CHAR");
            case 'S' -> primitiveType("SHORT");
            case 'I' -> primitiveType("INT");
            case 'J' -> primitiveType("LONG");
            case 'F' -> primitiveType("FLOAT");
            case 'D' -> primitiveType("DOUBLE");
            case 'L' -> {
                // Reference type: Ljava/lang/String; → java.lang.String
                final String fqn = desc.substring(1, desc.length() - 1).replace('/', '.');
                yield buildQualifiedName(fqn);
            }
            case '[' -> {
                // Array type: delegate to element type
                final Object elementType = buildTypeFromDescriptor(desc.substring(1));
                if (elementType == null || tmTypeArray == null) yield null;
                yield tmTypeArray.invoke(treeMaker, elementType);
            }
            default -> null;
        };
    }

    /** Builds {@code TreeMaker.TypeIdent(TypeTag.<tagName>)}. */
    private Object primitiveType(final String tagName) throws Exception {
        if (tmTypeIdent == null || typeTagClass == null) return null;
        // Safe: typeTagClass IS an enum class (com.sun.tools.javac.code.TypeTag).
        // Enum.valueOf requires a raw Class<Enum> parameter; the unchecked cast is
        // unavoidable here because typeTagClass was obtained via Class.forName() and
        // therefore has type Class<?>.  The call is correct at runtime because
        // typeTagClass is always the TypeTag enum class.
        @SuppressWarnings({"unchecked", "rawtypes"})
        final Enum<?> tag = Enum.valueOf((Class<Enum>) typeTagClass, tagName);
        return tmTypeIdent.invoke(treeMaker, tag);
    }

    // -------------------------------------------------------------------------
    // Idempotency check
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the class already declares a zero-parameter method
     * with the given name (avoids duplicate declarations on repeated processing rounds).
     */
    private boolean hasZeroArgMethod(final Object classDecl, final String name) {
        try {
            final Object defs = classDefsField.get(classDecl);
            for (final Object def : (Iterable<?>) defs) {
                if (!jcMethodDeclClass.isInstance(def)) continue;
                final Object defName = jcMethodNameField.get(def);
                if (!name.equals(defName.toString())) continue;
                final Object params = jcMethodParamsField.get(def);
                // com.sun.tools.javac.util.List.isEmpty()
                final Method isEmpty = params.getClass().getMethod("isEmpty");
                if ((boolean) isEmpty.invoke(params)) return true;
            }
        } catch (final Exception ignored) {}
        return false;
    }

    // -------------------------------------------------------------------------
    // defs mutation
    // -------------------------------------------------------------------------

    private void prependToDefs(final Object classDecl, final Object methodDecl) throws Exception {
        final Object currentDefs = classDefsField.get(classDecl);
        final Object newDefs = listPrepend.invoke(currentDefs, methodDecl);
        classDefsField.set(classDecl, newDefs);
    }

    // -------------------------------------------------------------------------
    // Entry-point name and caller-FQN resolution
    // -------------------------------------------------------------------------

    /**
     * Resolves the name of the entry-point method that will be injected into the
     * enclosing class (mirrors the logic in {@code BytecodeInjector}).
     */
    static String resolveEntryPointName(
            final MergeTree tree,
            final boolean isConstructorAnnot,
            final boolean isConstructorGroup) {
        if (isConstructorAnnot) return "constructor";
        if (isConstructorGroup) {
            final String enc = tree.group().enclosingClassName();
            final int slash = enc.lastIndexOf('/');
            final String simple = slash < 0 ? enc : enc.substring(slash + 1);
            return simple.toLowerCase(java.util.Locale.ROOT);
        }
        return tree.group().groupName();
    }

    /**
     * Returns the dot-separated FQN of the generated Invoker/Constructor class for the
     * given tree.  Mirrors the class-name logic in {@link rawit.processors.codegen.InvokerClassSpec}.
     */
    static String resolveCallerFqn(final MergeTree tree, final boolean isConstructorAnnot) {
        final String enc = tree.group().enclosingClassName();
        final int slash = enc.lastIndexOf('/');
        final String simple = slash < 0 ? enc : enc.substring(slash + 1);
        final String pkgDot = slash < 0 ? ""
                : enc.substring(0, slash).replace('/', '.') + ".generated.";

        if (isConstructorAnnot) return pkgDot + simple + "Constructor";
        final String groupName = tree.group().groupName();
        if ("<init>".equals(groupName)) return pkgDot + simple + "Invoker";
        return pkgDot + simple + toPascalCase(groupName) + "Invoker";
    }

    private static String toPascalCase(final String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // -------------------------------------------------------------------------
    // Reflection utilities
    // -------------------------------------------------------------------------

    /** Walks the class hierarchy to find {@code getContext()} on the processing env. */
    private static Object resolveContext(final ProcessingEnvironment env) {
        for (Class<?> c = env.getClass(); c != null; c = c.getSuperclass()) {
            try {
                final Method m = c.getDeclaredMethod("getContext");
                m.setAccessible(true);
                return m.invoke(env);
            } catch (final NoSuchMethodException ignored) {
                // continue up the hierarchy
            } catch (final Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Returns the first class in the context's hierarchy whose name is
     * {@code com.sun.tools.javac.util.Context}.
     */
    private static Class<?> resolveContextClass(final Object context) {
        for (Class<?> c = context.getClass(); c != null; c = c.getSuperclass()) {
            if ("com.sun.tools.javac.util.Context".equals(c.getName())) return c;
        }
        return null;
    }

    /** Finds a method by name and parameter count (returns the first match). */
    private static Method findMethod(
            final Class<?> clazz, final String name, final int paramCount) {
        for (final Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) return m;
        }
        return null;
    }

    /**
     * Finds a single-parameter method where the first (and only) parameter type is
     * assignable from {@code firstParamType}.
     */
    private static Method findMethodByFirstParamType(
            final Class<?> clazz, final String name, final Class<?> firstParamType) {
        for (final Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isAssignableFrom(firstParamType)) {
                return m;
            }
        }
        return null;
    }

    /** Returns {@code true} if any of the supplied objects is {@code null}. */
    private static boolean hasNull(final Object... objects) {
        for (final Object o : objects) {
            if (o == null) return true;
        }
        return false;
    }
}
