package rawit.processors.model;

/**
 * The merged stage tree for an {@link OverloadGroup}.
 *
 * <p>Wraps the root {@link MergeNode} of the tree together with the {@link OverloadGroup}
 * metadata that produced it.
 *
 * @param group the overload group this tree was built from
 * @param root  the root node of the merge tree
 */
public record MergeTree(OverloadGroup group, MergeNode root) {}
