package rawit.processors.model;

import java.util.List;

/**
 * A single node in the merge tree for an overload group.
 *
 * <p>The tree represents the unified stage graph produced by merging all overloads in an
 * {@link OverloadGroup}. Three variants are permitted:
 * <ul>
 *   <li>{@link SharedNode} — all overloads agree on parameter name and type at this position.</li>
 *   <li>{@link BranchingNode} — overloads diverge at this position.</li>
 *   <li>{@link TerminalNode} — one or more overloads end at this position.</li>
 * </ul>
 */
public sealed interface MergeNode permits MergeNode.SharedNode, MergeNode.BranchingNode, MergeNode.TerminalNode {

    /**
     * A position where all overloads agree on parameter name and type.
     *
     * @param paramName      the shared parameter name
     * @param typeDescriptor the shared JVM type descriptor
     * @param next           the next node in the chain (may be {@code null} if this is the last)
     * @param annotationFqns fully qualified names of tag annotations on this parameter (empty if none)
     */
    record SharedNode(
            String paramName,
            String typeDescriptor,
            MergeNode next,
            List<String> annotationFqns
    ) implements MergeNode {
        /** Defensive copy; defaults {@code annotationFqns} to empty if null. */
        public SharedNode {
            annotationFqns = annotationFqns == null ? List.of() : List.copyOf(annotationFqns);
        }

        /** Backward-compatible convenience constructor that delegates with an empty annotation list. */
        public SharedNode(String paramName, String typeDescriptor, MergeNode next) {
            this(paramName, typeDescriptor, next, List.of());
        }
    }

    /**
     * A position where overloads diverge into distinct (name, type) variants.
     *
     * @param branches one branch per distinct (name, type) variant
     */
    record BranchingNode(
            List<Branch> branches
    ) implements MergeNode {
        /** Defensive copy; makes {@code branches} unmodifiable. */
        public BranchingNode {
            branches = List.copyOf(branches);
        }
    }

    /**
     * A terminal position where one or more overloads end.
     *
     * @param overloads    the overload(s) that terminate at this node
     * @param continuation non-{@code null} if longer overloads continue past this node
     */
    record TerminalNode(
            List<AnnotatedMethod> overloads,
            MergeNode continuation
    ) implements MergeNode {
        /** Defensive copy; makes {@code overloads} unmodifiable. */
        public TerminalNode {
            overloads = List.copyOf(overloads);
        }
    }

    /**
     * A single branch within a {@link BranchingNode}.
     *
     * @param paramName      the parameter name for this branch
     * @param typeDescriptor the JVM type descriptor for this branch
     * @param next           the next node following this branch
     * @param annotationFqns fully qualified names of tag annotations on this parameter (empty if none)
     */
    record Branch(
            String paramName,
            String typeDescriptor,
            MergeNode next,
            List<String> annotationFqns
    ) {
        /** Defensive copy; defaults {@code annotationFqns} to empty if null. */
        public Branch {
            annotationFqns = annotationFqns == null ? List.of() : List.copyOf(annotationFqns);
        }

        /** Backward-compatible convenience constructor that delegates with an empty annotation list. */
        public Branch(String paramName, String typeDescriptor, MergeNode next) {
            this(paramName, typeDescriptor, next, List.of());
        }
    }
}
