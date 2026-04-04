package rawit.processors.model;

import java.util.List;

/**
 * A named group of {@link AnnotatedMethod}s that share the same enclosing class and method name.
 *
 * @param enclosingClassName binary name of the enclosing class, e.g. {@code "com/example/Foo"}
 * @param groupName          method name shared by all members, or {@code "<init>"} for constructors
 * @param members            the annotated methods belonging to this group
 */
public record OverloadGroup(
        String enclosingClassName,
        String groupName,
        List<AnnotatedMethod> members
) {
    /** Defensive copy; makes {@code members} unmodifiable. */
    public OverloadGroup {
        members = List.copyOf(members);
    }
}
