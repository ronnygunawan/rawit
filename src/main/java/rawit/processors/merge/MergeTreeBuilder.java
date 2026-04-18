package rawit.processors.merge;

import rawit.processors.model.AnnotatedMethod;
import rawit.processors.model.MergeNode;
import rawit.processors.model.MergeNode.Branch;
import rawit.processors.model.MergeNode.BranchingNode;
import rawit.processors.model.MergeNode.SharedNode;
import rawit.processors.model.MergeNode.TerminalNode;
import rawit.processors.model.MergeTree;
import rawit.processors.model.OverloadGroup;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link MergeTree} from an {@link OverloadGroup} using Algorithm 1 from the design.
 *
 * <p>The algorithm recursively partitions overloads at each parameter position into:
 * <ul>
 *   <li><em>terminals</em> — overloads whose parameter list ends at this position</li>
 *   <li><em>continuations</em> — overloads that have more parameters beyond this position</li>
 * </ul>
 *
 * <p>Continuations are grouped by {@code (name, type)} at the current position:
 * <ul>
 *   <li>Single group → {@link SharedNode}</li>
 *   <li>Multiple groups → {@link BranchingNode}</li>
 *   <li>Same name, different types → conflict; {@link Diagnostic.Kind#ERROR} emitted, returns {@code null}</li>
 * </ul>
 */
public class MergeTreeBuilder {

    private final Messager messager;

    /**
     * Creates a new {@code MergeTreeBuilder}.
     *
     * @param messager the compiler messager used to emit conflict diagnostics
     */
    public MergeTreeBuilder(final Messager messager) {
        this.messager = messager;
    }

    /**
     * Builds a {@link MergeTree} for the given overload group.
     *
     * @param group the overload group to merge
     * @return the merge tree, or {@code null} if a conflict was detected
     */
    public MergeTree build(final OverloadGroup group) {
        final MergeNode root = buildNode(group.members(), 0);
        if (root == null) {
            return null;
        }
        return new MergeTree(group, root);
    }

    // -------------------------------------------------------------------------
    // Algorithm 1 — recursive merge tree construction
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link MergeNode} for the given overloads at the given parameter position.
     *
     * @param overloads overloads still active at this position
     * @param position  current parameter index (0-based)
     * @return the node, or {@code null} on conflict
     */
    private MergeNode buildNode(final List<AnnotatedMethod> overloads, final int position) {
        if (overloads.isEmpty()) {
            return null;
        }

        // Partition into terminals (end here) and continuations (have more params)
        final List<AnnotatedMethod> terminals = new ArrayList<>();
        final List<AnnotatedMethod> continuations = new ArrayList<>();

        for (final AnnotatedMethod m : overloads) {
            if (m.parameters().size() == position) {
                terminals.add(m);
            } else {
                continuations.add(m);
            }
        }

        final MergeNode continuationNode = buildContinuation(continuations, position);
        // null return from buildContinuation signals a conflict
        if (continuationNode == null && !continuations.isEmpty()) {
            return null;
        }

        if (!terminals.isEmpty()) {
            return new TerminalNode(terminals, continuationNode);
        }
        return continuationNode;
    }

    /**
     * Builds the continuation sub-tree for overloads that have a parameter at {@code position}.
     *
     * @param overloads overloads with at least one more parameter at {@code position}
     * @param position  current parameter index
     * @return the continuation node, or {@code null} on conflict (or empty input)
     */
    private MergeNode buildContinuation(final List<AnnotatedMethod> overloads, final int position) {
        if (overloads.isEmpty()) {
            return null;
        }

        // Group by (name, type) at this position — preserving insertion order for determinism
        final Map<String, Map<String, List<AnnotatedMethod>>> byNameThenType = new LinkedHashMap<>();
        for (final AnnotatedMethod m : overloads) {
            final String name = m.parameters().get(position).name();
            final String type = m.parameters().get(position).typeDescriptor();
            byNameThenType
                    .computeIfAbsent(name, k -> new LinkedHashMap<>())
                    .computeIfAbsent(type, k -> new ArrayList<>())
                    .add(m);
        }

        // Detect same-name-different-type conflict (Requirement 14.1)
        for (final Map.Entry<String, Map<String, List<AnnotatedMethod>>> nameEntry : byNameThenType.entrySet()) {
            final String paramName = nameEntry.getKey();
            final Map<String, List<AnnotatedMethod>> typeMap = nameEntry.getValue();
            if (typeMap.size() > 1) {
                // Collect all conflicting overloads and emit one ERROR per overload
                final List<String> conflictingTypes = new ArrayList<>(typeMap.keySet());
                final String message = "conflicting types for parameter '" + paramName
                        + "': " + conflictingTypes;
                for (final List<AnnotatedMethod> conflictGroup : typeMap.values()) {
                    for (final AnnotatedMethod m : conflictGroup) {
                        messager.printMessage(Diagnostic.Kind.ERROR, message);
                    }
                }
                return null;
            }
        }

        // Flatten to (name, type) → members map
        final Map<String, List<AnnotatedMethod>> byNameType = new LinkedHashMap<>();
        for (final Map.Entry<String, Map<String, List<AnnotatedMethod>>> nameEntry : byNameThenType.entrySet()) {
            final String name = nameEntry.getKey();
            // Only one type per name at this point (conflict already checked above)
            for (final Map.Entry<String, List<AnnotatedMethod>> typeEntry : nameEntry.getValue().entrySet()) {
                final String key = name + "\0" + typeEntry.getKey();
                byNameType.put(key, typeEntry.getValue());
            }
        }

        if (byNameType.size() == 1) {
            // All continuations agree on (name, type) — SharedNode
            final Map.Entry<String, List<AnnotatedMethod>> entry = byNameType.entrySet().iterator().next();
            final String[] parts = entry.getKey().split("\0", 2);
            final String paramName = parts[0];
            final String typeDescriptor = parts[1];
            final List<String> annotationFqns = entry.getValue().get(0).parameters().get(position).annotationFqns();
            final MergeNode next = buildNode(entry.getValue(), position + 1);
            if (next == null && !entry.getValue().isEmpty()) {
                // Propagate conflict from deeper level
                return null;
            }
            return new SharedNode(paramName, typeDescriptor, next, annotationFqns);
        }

        // Multiple distinct (name, type) groups — BranchingNode
        final List<Branch> branches = new ArrayList<>();
        for (final Map.Entry<String, List<AnnotatedMethod>> entry : byNameType.entrySet()) {
            final String[] parts = entry.getKey().split("\0", 2);
            final String paramName = parts[0];
            final String typeDescriptor = parts[1];
            final List<String> annotationFqns = entry.getValue().get(0).parameters().get(position).annotationFqns();
            final MergeNode next = buildNode(entry.getValue(), position + 1);
            if (next == null && !entry.getValue().isEmpty()) {
                return null;
            }
            branches.add(new Branch(paramName, typeDescriptor, next, annotationFqns));
        }
        return new BranchingNode(branches);
    }
}
