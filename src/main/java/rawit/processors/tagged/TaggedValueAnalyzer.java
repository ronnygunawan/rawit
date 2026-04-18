package rawit.processors.tagged;

import rawit.processors.model.TagInfo;
import rawit.processors.model.TagResolution;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AST scanner that inspects assignment-like expressions for tagged value
 * safety violations.
 *
 * <p>Uses the javac Tree API ({@code com.sun.source.tree.TreePathScanner})
 * to walk compilation units and inspect:
 * <ul>
 *   <li>Variable declarations with initializers (local variables, fields)</li>
 *   <li>Assignment expressions</li>
 *   <li>Method invocation arguments (including builder chain stage methods whose
 *       parameters carry tag annotations)</li>
 *   <li>Return statements</li>
 * </ul>
 *
 * <p>For each assignment-like expression, the analyzer:
 * <ol>
 *   <li>Resolves the target tag via {@link TagResolver}</li>
 *   <li>Resolves the source tag via {@link TagResolver}</li>
 *   <li>Determines if the RHS is a literal or compile-time constant</li>
 *   <li>Delegates to {@link AssignmentChecker} for the warning decision</li>
 *   <li>Emits the warning via {@code Messager} if applicable</li>
 * </ol>
 *
 * <p>This class is lightweight and reusable. It maintains internal caches
 * to deduplicate warnings but requires no external initialization.
 *
 * <p>Graceful degradation: if the Tree API is unavailable (e.g. running under
 * ECJ), analysis is silently skipped.
 */
public final class TaggedValueAnalyzer {

    private final TagResolver tagResolver = new TagResolver();
    private final AssignmentChecker assignmentChecker = new AssignmentChecker();

    /**
     * Convenience method that iterates all compilation units in the round
     * and delegates to {@link #analyze} for each.
     *
     * <p>Gracefully degrades: if the Tree API is unavailable, this method
     * silently returns without performing any analysis.
     *
     * @param tagMap        the known tag annotations (FQN → {@link TagInfo})
     * @param roundEnv      the current round environment
     * @param processingEnv the processing environment
     * @param analyzedUnits compilation units already analyzed across rounds (prevents duplicate warnings)
     */
    public void analyzeRound(
            final Map<String, TagInfo> tagMap,
            final RoundEnvironment roundEnv,
            final ProcessingEnvironment processingEnv,
            final java.util.Set<java.net.URI> analyzedUnits
    ) {
        try {
            doAnalyzeRound(tagMap, roundEnv, processingEnv, analyzedUnits);
        } catch (final NoClassDefFoundError ignored) {
            // Tree API not available (e.g. ECJ) — silently skip
        } catch (final UnsupportedOperationException ignored) {
            // Tree API not supported — silently skip
        }
    }

    /**
     * Internal implementation that uses the Tree API. Separated so that
     * {@link #analyzeRound} can catch class-loading failures.
     */
    private void doAnalyzeRound(
            final Map<String, TagInfo> tagMap,
            final RoundEnvironment roundEnv,
            final ProcessingEnvironment processingEnv,
            final java.util.Set<java.net.URI> analyzedUnits
    ) {
        final com.sun.source.util.Trees trees;
        try {
            trees = com.sun.source.util.Trees.instance(processingEnv);
        } catch (final IllegalArgumentException ignored) {
            // Cannot obtain Trees instance — silently skip
            return;
        }

        // Iterate all root elements in the round and analyze each compilation unit once
        for (final Element rootElement : roundEnv.getRootElements()) {
            if (!(rootElement instanceof TypeElement)) {
                continue;
            }
            try {
                final com.sun.source.util.TreePath treePath = trees.getPath(rootElement);
                if (treePath == null) {
                    continue;
                }
                final com.sun.source.tree.CompilationUnitTree compilationUnit =
                        treePath.getCompilationUnit();
                if (compilationUnit == null || compilationUnit.getSourceFile() == null) {
                    continue;
                }
                final java.net.URI sourceUri = compilationUnit.getSourceFile().toUri();
                if (!analyzedUnits.add(sourceUri)) {
                    continue;
                }
                analyze(tagMap, compilationUnit, trees, processingEnv);
            } catch (final NullPointerException ignored) {
                // Tree path not available for this element — skip
            }
        }
    }

    /**
     * Analyzes a single compilation unit for tagged value assignment violations.
     *
     * @param tagMap          the known tag annotations (FQN → {@link TagInfo})
     * @param compilationUnit the compilation unit AST to scan
     * @param trees           the Trees utility for resolving elements from tree paths
     * @param env             the processing environment
     */
    public void analyze(
            final Map<String, TagInfo> tagMap,
            final com.sun.source.tree.CompilationUnitTree compilationUnit,
            final com.sun.source.util.Trees trees,
            final ProcessingEnvironment env
    ) {
        final javax.annotation.processing.Messager messager = env.getMessager();

        new com.sun.source.util.TreePathScanner<Void, Void>() {

            /** Methods already warned about having multiple return type tags. */
            private final java.util.Set<ExecutableElement> warnedReturnTypeMethods =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

            // --- Variable declarations with initializers ---
            @Override
            public Void visitVariable(
                    final com.sun.source.tree.VariableTree node,
                    final Void unused
            ) {
                final com.sun.source.tree.ExpressionTree initializer = node.getInitializer();
                if (initializer != null) {
                    final com.sun.source.util.TreePath varPath = getCurrentPath();
                    final Element targetElement = trees.getElement(varPath);
                    if (targetElement != null) {
                        checkAssignment(targetElement, initializer, varPath, node);
                    }
                }
                return super.visitVariable(node, unused);
            }

            // --- Assignment expressions ---
            @Override
            public Void visitAssignment(
                    final com.sun.source.tree.AssignmentTree node,
                    final Void unused
            ) {
                final com.sun.source.util.TreePath assignPath = getCurrentPath();
                final com.sun.source.util.TreePath lhsPath =
                        new com.sun.source.util.TreePath(assignPath, node.getVariable());
                final Element targetElement = trees.getElement(lhsPath);
                if (targetElement != null) {
                    checkAssignment(targetElement, node.getExpression(), assignPath, node);
                }
                return super.visitAssignment(node, unused);
            }

            // --- Method invocation arguments ---
            @Override
            public Void visitMethodInvocation(
                    final com.sun.source.tree.MethodInvocationTree node,
                    final Void unused
            ) {
                final com.sun.source.util.TreePath invocationPath = getCurrentPath();
                final Element methodElement = trees.getElement(invocationPath);
                if (methodElement instanceof ExecutableElement executableElement) {
                    final List<? extends VariableElement> params = executableElement.getParameters();
                    final List<? extends com.sun.source.tree.ExpressionTree> args =
                            node.getArguments();
                    final int count = Math.min(params.size(), args.size());
                    for (int i = 0; i < count; i++) {
                        final VariableElement param = params.get(i);
                        final com.sun.source.tree.ExpressionTree arg = args.get(i);
                        checkAssignment(param, arg, invocationPath, node);
                    }
                }
                return super.visitMethodInvocation(node, unused);
            }

            // --- Return statements ---
            @Override
            public Void visitReturn(
                    final com.sun.source.tree.ReturnTree node,
                    final Void unused
            ) {
                final com.sun.source.tree.ExpressionTree expr = node.getExpression();
                if (expr != null) {
                    final ExecutableElement enclosingMethod = findEnclosingMethod();
                    if (enclosingMethod != null) {
                        checkReturnAssignment(enclosingMethod, expr, getCurrentPath(), node);
                    }
                }
                return super.visitReturn(node, unused);
            }

            /**
             * Validates a return expression against the enclosing method's tagged return type.
             * Type-use annotations on the return type live on {@code method.getReturnType()},
             * not on the method element itself, so prefer the return type as the target.
             */
            private void checkReturnAssignment(
                    final ExecutableElement enclosingMethod,
                    final com.sun.source.tree.ExpressionTree expression,
                    final com.sun.source.util.TreePath contextPath,
                    final com.sun.source.tree.Tree contextNode
            ) {
                // Resolve target tag from return type annotations first
                final TagResolution returnTag = resolveMethodReturnTag(enclosingMethod);
                if (returnTag instanceof TagResolution.Tagged) {
                    // Use a synthetic check with the resolved return tag
                    try {
                        final TagResolution sourceTag = resolveSourceTag(expression, contextPath);
                        final boolean isLiteralOrConst = isLiteralOrConstant(expression, contextPath);
                        final Optional<AssignmentWarning> warning =
                                assignmentChecker.check(sourceTag, returnTag, isLiteralOrConst);
                        warning.ifPresent(w -> trees.printMessage(
                                Diagnostic.Kind.WARNING,
                                w.toMessage(),
                                contextNode,
                                contextPath.getCompilationUnit()));
                    } catch (final NullPointerException | IllegalArgumentException ignored) {
                        // Unresolvable elements — skip
                    }
                    return;
                }
                // Fall back to method element annotations
                checkAssignment(enclosingMethod, expression, contextPath, contextNode);
            }

            /**
             * Finds the enclosing method element by walking up the tree path.
             */
            private ExecutableElement findEnclosingMethod() {
                com.sun.source.util.TreePath path = getCurrentPath();
                while (path != null) {
                    final com.sun.source.tree.Tree leaf = path.getLeaf();
                    if (leaf instanceof com.sun.source.tree.MethodTree) {
                        final Element element = trees.getElement(path);
                        if (element instanceof ExecutableElement exec) {
                            return exec;
                        }
                    }
                    path = path.getParentPath();
                }
                return null;
            }

            /**
             * Core check: resolves tags for target and source, determines if
             * the source is a literal/constant, delegates to AssignmentChecker,
             * and emits a warning if applicable.
             *
             * @param targetElement the target (LHS) element
             * @param sourceExpr   the source (RHS) expression tree
             * @param contextPath  the tree path for context
             * @param contextTree  the tree node for diagnostic positioning
             */
            private void checkAssignment(
                    final Element targetElement,
                    final com.sun.source.tree.ExpressionTree sourceExpr,
                    final com.sun.source.util.TreePath contextPath,
                    final com.sun.source.tree.Tree contextTree
            ) {
                try {
                    // Resolve target tag
                    final TagResolution targetTag =
                            tagResolver.resolve(targetElement, tagMap, messager);

                    // Resolve source tag
                    final TagResolution sourceTag = resolveSourceTag(sourceExpr, contextPath);

                    // Determine if source is a literal or compile-time constant
                    final boolean isLiteralOrConst = isLiteralOrConstant(sourceExpr, contextPath);

                    // Delegate to AssignmentChecker
                    final Optional<AssignmentWarning> warning =
                            assignmentChecker.check(sourceTag, targetTag, isLiteralOrConst);

                    // Emit warning at the assignment/call site when possible
                    warning.ifPresent(w -> {
                        if (contextTree != null) {
                            trees.printMessage(
                                    Diagnostic.Kind.WARNING,
                                    w.toMessage(),
                                    contextTree,
                                    compilationUnit
                            );
                        } else {
                            messager.printMessage(
                                    Diagnostic.Kind.WARNING,
                                    w.toMessage(),
                                    targetElement
                            );
                        }
                    });
                } catch (final NullPointerException | IllegalArgumentException ignored) {
                    // Unresolvable elements during analysis — skip this assignment
                }
            }

            /**
             * Resolves the tag of a source (RHS) expression by finding its
             * underlying element and inspecting annotations.
             */
            private TagResolution resolveSourceTag(
                    final com.sun.source.tree.ExpressionTree expr,
                    final com.sun.source.util.TreePath parentPath
            ) {
                final com.sun.source.util.TreePath exprPath =
                        new com.sun.source.util.TreePath(parentPath, expr);
                final Element sourceElement = trees.getElement(exprPath);
                if (sourceElement != null) {
                    // For method invocations, use the return type's annotations
                    if (sourceElement instanceof ExecutableElement execElement) {
                        // Resolve tag from the method's return type annotations
                        // and the method element's own annotations
                        return resolveMethodReturnTag(execElement);
                    }
                    return tagResolver.resolve(sourceElement, tagMap, messager);
                }
                // Cannot resolve — treat as untagged
                return new TagResolution.Untagged();
            }

            /**
             * Resolves the tag for a method's return value by inspecting
             * annotations on the method's return type, with lazy discovery
             * and duplicate-tag handling.
             */
            private TagResolution resolveMethodReturnTag(
                    final ExecutableElement method
            ) {
                final List<? extends javax.lang.model.element.AnnotationMirror> returnAnnotations =
                        method.getReturnType().getAnnotationMirrors();
                TagResolution.Tagged firstTag = null;
                for (final javax.lang.model.element.AnnotationMirror mirror : returnAnnotations) {
                    final Element annotationElement = mirror.getAnnotationType().asElement();
                    if (annotationElement instanceof TypeElement typeElement) {
                        final String fqn = typeElement.getQualifiedName().toString();
                        TagInfo info = tagMap.get(fqn);
                        if (info == null) {
                            // Lazy discovery: check if this annotation type
                            // is meta-annotated with @TaggedValue
                            info = TagResolver.lazyDiscover(typeElement, tagMap);
                        }
                        if (info != null) {
                            if (firstTag != null) {
                                if (warnedReturnTypeMethods.add(method)) {
                                    final String firstName = simpleName(firstTag.tag().annotationFqn());
                                    final String secondName = simpleName(info.annotationFqn());
                                    messager.printMessage(
                                            Diagnostic.Kind.WARNING,
                                            "multiple tag annotations on return type; using @"
                                                    + firstName + ", ignoring @" + secondName,
                                            method
                                    );
                                }
                                return firstTag;
                            }
                            firstTag = new TagResolution.Tagged(info);
                        }
                    }
                }
                if (firstTag != null) {
                    return firstTag;
                }
                // Fall back to annotations on the method element itself
                return tagResolver.resolve(method, tagMap, messager);
            }

            /**
             * Extracts the simple name from a fully qualified name.
             */
            private String simpleName(final String fqn) {
                final int dot = fqn.lastIndexOf('.');
                return dot < 0 ? fqn : fqn.substring(dot + 1);
            }

            /**
             * Determines whether an expression is a literal or compile-time constant.
             *
             * <p>Checks for:
             * <ul>
             *   <li>{@code LiteralTree} — numeric, string, boolean, char, null literals</li>
             *   <li>{@code UnaryTree} — unary constant expressions (e.g. {@code -1L}, {@code +1}, {@code ~0})</li>
             *   <li>{@code IdentifierTree} referencing a variable with a constant value</li>
             *   <li>{@code MemberSelectTree} referencing a qualified constant (e.g. {@code Constants.VALUE})</li>
             *   <li>{@code ParenthesizedTree} wrapping any of the above</li>
             * </ul>
             */
            private boolean isLiteralOrConstant(
                    final com.sun.source.tree.ExpressionTree expr,
                    final com.sun.source.util.TreePath parentPath
            ) {
                if (expr instanceof com.sun.source.tree.LiteralTree) {
                    return true;
                }
                if (expr instanceof com.sun.source.tree.ParenthesizedTree paren) {
                    return isLiteralOrConstant(paren.getExpression(), parentPath);
                }
                if (expr instanceof com.sun.source.tree.UnaryTree unary) {
                    return isLiteralOrConstant(unary.getExpression(), parentPath);
                }
                if (expr instanceof com.sun.source.tree.IdentifierTree
                        || expr instanceof com.sun.source.tree.MemberSelectTree) {
                    final com.sun.source.util.TreePath exprPath =
                            new com.sun.source.util.TreePath(parentPath, expr);
                    final Element element = trees.getElement(exprPath);
                    if (element instanceof VariableElement varElement) {
                        return varElement.getConstantValue() != null;
                    }
                }
                return false;
            }

        }.scan(compilationUnit, null);
    }
}
