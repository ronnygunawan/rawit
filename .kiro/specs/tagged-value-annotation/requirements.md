# Requirements Document

## Introduction

This feature adds a new `@TaggedValue` meta-annotation to the rawit library. `@TaggedValue` enables users to declare value type tags as custom annotations (e.g., `@UserId`, `@FirstName`, `@LastName`) that can be applied to fields, parameters, and local variables. A companion compile-time analyzer detects potentially unsafe assignments between tagged and untagged values, and between values with mismatched tags. The `strict` attribute controls whether assignments from a tagged type to an untagged type produce warnings (strict mode) or are silently allowed (lax mode). This provides a lightweight, non-invasive approach to value type safety without requiring wrapper types.

## Glossary

- **TaggedValue_Annotation**: The `@TaggedValue` meta-annotation type, targeting `ElementType.ANNOTATION_TYPE` with `RetentionPolicy.CLASS`. Users apply this to their own annotation declarations to define value type tags.
- **Tag_Annotation**: A user-defined annotation that is itself annotated with `@TaggedValue`. Examples: `@UserId`, `@FirstName`, `@LastName`.
- **Tagged_Element**: A field, parameter, local variable, or method return type that carries a Tag_Annotation.
- **Untagged_Element**: A field, parameter, local variable, or method return type that does not carry any Tag_Annotation.
- **Tag_Mismatch**: An assignment or binding where the source and target carry different Tag_Annotations (e.g., assigning a `@FirstName String` to a `@LastName String`).
- **Strict_Mode**: When `@TaggedValue(strict = true)` is set on a Tag_Annotation, assignments from a Tagged_Element with that tag to an Untagged_Element produce a compiler warning.
- **Lax_Mode**: When `@TaggedValue(strict = false)` (the default) is set on a Tag_Annotation, assignments from a Tagged_Element with that tag to an Untagged_Element are silently allowed.
- **TaggedValue_Analyzer**: The component within the rawit annotation processor that inspects assignments involving Tagged_Elements and emits compiler warnings for unsafe assignments.
- **Rawit_Processor**: The existing `RawitAnnotationProcessor` that handles `@Invoker`, `@Constructor`, and `@Getter` annotations.
- **Assignment**: Any value transfer including direct variable assignment, method argument passing, method return, and constructor argument passing.

## Requirements

### Requirement 1: TaggedValue Meta-Annotation Declaration

**User Story:** As a library author, I want to provide a `@TaggedValue` meta-annotation, so that users can declare their own value type tags without modifying the rawit library.

#### Acceptance Criteria

1. THE TaggedValue_Annotation SHALL target only `ElementType.ANNOTATION_TYPE`.
2. THE TaggedValue_Annotation SHALL have retention `RetentionPolicy.CLASS` so that the tag information is available during annotation processing of downstream code.
3. THE TaggedValue_Annotation SHALL declare a `boolean strict()` attribute with a default value of `false`.
4. WHEN a user annotates a custom annotation declaration with `@TaggedValue`, THE custom annotation SHALL become a valid Tag_Annotation recognized by the TaggedValue_Analyzer.

#### Code Example

```java
// The @TaggedValue meta-annotation declaration
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface TaggedValue {
    boolean strict() default false;
}

// User-defined Tag_Annotations created using @TaggedValue
@TaggedValue(strict = true)
public @interface UserId { }

@TaggedValue               // strict defaults to false (lax mode)
public @interface FirstName { }

@TaggedValue
public @interface LastName { }
```

### Requirement 2: Tag Annotation Target Flexibility

**User Story:** As a developer, I want to apply my Tag_Annotations to fields, parameters, local variables, and method return types, so that I can tag values throughout my codebase.

#### Acceptance Criteria

1. THE TaggedValue_Analyzer SHALL recognize Tag_Annotations applied to method parameters.
2. THE TaggedValue_Analyzer SHALL recognize Tag_Annotations applied to fields.
3. THE TaggedValue_Analyzer SHALL recognize Tag_Annotations applied to local variables.
4. THE TaggedValue_Analyzer SHALL recognize Tag_Annotations applied as type annotations on method return types.
5. THE TaggedValue_Analyzer SHALL recognize Tag_Annotations applied to record components.

#### Code Example

```java
// Record components (also serves as constructor parameters)
@Constructor
public record User(
    @UserId long userId,
    @FirstName String firstName,
    @LastName String lastName
) { }

// Field usage
class UserService {
    @UserId private long currentUserId;

    // Method parameter usage
    void updateName(@FirstName String first, @LastName String last) { ... }

    // Local variable usage
    void process() {
        @FirstName String name = user.firstName();
    }
}
```

### Requirement 3: Warning on Assignment from Untagged to Strict Tagged

**User Story:** As a developer, I want to receive a compiler warning when assigning an untagged value to a variable with a strict tag, so that I am aware of potential type safety violations for sensitive values.

#### Acceptance Criteria

1. WHEN an Untagged_Element value is assigned to a Tagged_Element whose Tag_Annotation has `strict = true`, THE TaggedValue_Analyzer SHALL emit a compiler warning indicating that an untagged value is being assigned to a strict tagged type.
2. WHEN a literal or constant value (without a tag) is assigned to a Tagged_Element whose Tag_Annotation has `strict = true`, THE TaggedValue_Analyzer SHALL produce no warning. Literals and constants are always exempt from untagged-to-tagged warnings.

#### Code Example

```java
@UserId long taggedId = 42;       // No warning: literal → strict tagged (literals are always exempt)

long rawId = getUserId();         // untagged value from a method call
@UserId long taggedId2 = rawId;   // WARNING: untagged → strict tagged (@UserId has strict = true)
```

### Requirement 4: No Warning on Assignment from Untagged to Lax Tagged

**User Story:** As a developer, I want assignments from untagged values to lax-tagged variables to be silently accepted, so that I can adopt tagging incrementally without being overwhelmed by warnings in partially-tagged codebases.

#### Acceptance Criteria

1. WHEN an Untagged_Element value is assigned to a Tagged_Element whose Tag_Annotation has `strict = false` (Lax_Mode), THE TaggedValue_Analyzer SHALL produce no warning.

#### Code Example

```java
String rawName = "John";                  // untagged value
@FirstName String taggedName = rawName;   // No warning: untagged → lax tagged (@FirstName has strict = false)
```

### Requirement 5: Warning on Assignment from Tagged to Untagged in Strict Mode

**User Story:** As a developer, I want to receive a compiler warning when assigning a strict-tagged value to an untagged variable, so that I can prevent accidental leakage of sensitive tagged values into untyped contexts.

#### Acceptance Criteria

1. WHEN a Tagged_Element value whose Tag_Annotation has `strict = true` is assigned to an Untagged_Element, THE TaggedValue_Analyzer SHALL emit a compiler warning indicating that a strict tagged value is being assigned to an untagged type.

#### Code Example

```java
@Constructor
public record User(@UserId long userId, @FirstName String firstName, @LastName String lastName) { }

final User user = User.constructor().userId(10).firstName("John").lastName("Doe").construct();

final long id = user.userId();   // WARNING: strict tagged (@UserId) → untagged
```

### Requirement 6: No Warning on Assignment from Tagged to Untagged in Lax Mode

**User Story:** As a developer, I want assignments from lax-tagged values to untagged variables to be silently accepted, so that lax tags remain compatible with existing DTOs and entity models that lack annotations.

#### Acceptance Criteria

1. WHEN a Tagged_Element value whose Tag_Annotation has `strict = false` (Lax_Mode) is assigned to an Untagged_Element, THE TaggedValue_Analyzer SHALL produce no warning.

#### Code Example

```java
final String firstName = user.firstName();   // No warning: lax tagged (@FirstName) → untagged
```

### Requirement 7: Warning on Tag Mismatch Assignment

**User Story:** As a developer, I want to receive a compiler warning when assigning a value with one tag to a variable with a different tag, so that I can prevent accidental misassignment of semantically distinct values (e.g., assigning a `@FirstName` value to a `@LastName` variable).

#### Acceptance Criteria

1. WHEN a Tagged_Element value with Tag_Annotation A is assigned to a Tagged_Element with a different Tag_Annotation B, THE TaggedValue_Analyzer SHALL emit a compiler warning indicating a tag mismatch between A and B.
2. THE TaggedValue_Analyzer SHALL emit the tag mismatch warning regardless of the `strict` attribute values on either Tag_Annotation.

#### Code Example

```java
final @LastName String lastName = user.firstName();   // WARNING: tag mismatch (@FirstName → @LastName)
```

### Requirement 8: No Warning on Matching Tag Assignment

**User Story:** As a developer, I want assignments between values with the same tag to be silently accepted, so that correctly-tagged code does not produce spurious warnings.

#### Acceptance Criteria

1. WHEN a Tagged_Element value with Tag_Annotation A is assigned to a Tagged_Element with the same Tag_Annotation A, THE TaggedValue_Analyzer SHALL produce no warning.

#### Code Example

```java
@FirstName String name1 = user.firstName();
@FirstName String name2 = name1;              // No warning: same tag (@FirstName → @FirstName)
```

### Requirement 9: No Warning on Untagged to Untagged Assignment

**User Story:** As a developer, I want assignments between untagged values to remain unaffected by the TaggedValue_Analyzer, so that existing code without tags continues to compile without new warnings.

#### Acceptance Criteria

1. WHEN an Untagged_Element value is assigned to another Untagged_Element, THE TaggedValue_Analyzer SHALL produce no warning.

#### Code Example

```java
String a = "hello";
String b = a;           // No warning: untagged → untagged (no tags involved)
```

### Requirement 10: Interaction with Generated Builder Chains

**User Story:** As a developer, I want the TaggedValue_Analyzer to analyze assignments into builder stage methods generated by `@Constructor` and `@Invoker`, so that tagged value safety extends to the fluent APIs generated by rawit.

#### Acceptance Criteria

1. WHEN a value is passed as an argument to a stage method in a generated builder chain (from `@Constructor` or `@Invoker`), THE TaggedValue_Analyzer SHALL apply the same tag-checking rules (Requirements 3–9) using the tag on the original constructor or method parameter as the target tag.
2. WHEN a literal or constant value (without a tag) is passed to a stage method, THE TaggedValue_Analyzer SHALL produce no warning regardless of the `strict` attribute on the target parameter's Tag_Annotation. Literals and constants are always exempt.
3. WHEN rawit generates stage interface methods and terminal methods from `@Constructor` or `@Invoker` annotated elements, THE code generator SHALL propagate Tag_Annotations from the original constructor or method parameters onto the corresponding generated stage method parameters, so that the TaggedValue_Analyzer can detect tag violations at the call site without requiring the analyzer to map generated methods back to their originals.

#### Code Example

```java
@Constructor
public record User(
    @UserId long userId,
    @FirstName String firstName,
    @LastName String lastName
) { }

final User user = User.constructor()
    .userId(10)            // No warning: literals are always exempt (even though @UserId has strict = true)
    .firstName("John")     // No warning: literal → lax tagged
    .lastName("Doe")       // No warning: literal → lax tagged
    .construct();

@FirstName String name = "John";
final User user2 = User.constructor()
    .userId(10)            // No warning: literal exempt
    .firstName(name)       // No warning: @FirstName → @FirstName (matching tags)
    .lastName(name)        // WARNING: tag mismatch (@FirstName → @LastName)
    .construct();
```

### Requirement 11: Multiple Tags on a Single Element

**User Story:** As a developer, I want the analyzer to handle the case where multiple Tag_Annotations are applied to the same element, so that the behavior is well-defined and predictable.

#### Acceptance Criteria

1. WHEN multiple Tag_Annotations are applied to the same element, THE TaggedValue_Analyzer SHALL use the first Tag_Annotation encountered (in declaration order) as the effective tag for that element.
2. WHEN multiple Tag_Annotations are applied to the same element, THE TaggedValue_Analyzer SHALL emit a compiler warning indicating that multiple tags are present and only the first is used.

### Requirement 12: Diagnostic Message Quality

**User Story:** As a developer, I want clear and actionable compiler warning messages from the TaggedValue_Analyzer, so that I can quickly understand and resolve tag safety issues.

#### Acceptance Criteria

1. WHEN the TaggedValue_Analyzer emits a tag mismatch warning, THE warning message SHALL include the names of both the source Tag_Annotation and the target Tag_Annotation.
2. WHEN the TaggedValue_Analyzer emits a strict-mode warning (tagged-to-untagged or untagged-to-tagged), THE warning message SHALL include the name of the Tag_Annotation involved.
3. THE TaggedValue_Analyzer SHALL emit all warnings using `Diagnostic.Kind.WARNING` so that compilation succeeds but the developer is informed.

### Requirement 13: Processor Integration

**User Story:** As a developer, I want the TaggedValue_Analyzer to be integrated into the existing rawit annotation processor, so that no additional processor configuration is required.

#### Acceptance Criteria

1. THE Rawit_Processor SHALL register `rawit.TaggedValue` as a supported annotation type alongside the existing `rawit.Invoker`, `rawit.Constructor`, and `rawit.Getter` annotations.
2. WHEN the Rawit_Processor encounters elements annotated with Tag_Annotations (annotations that are themselves annotated with `@TaggedValue`), THE Rawit_Processor SHALL delegate analysis to the TaggedValue_Analyzer.
3. THE TaggedValue_Analyzer SHALL operate within the same compilation round as the existing annotation processing, requiring no additional compiler passes.
