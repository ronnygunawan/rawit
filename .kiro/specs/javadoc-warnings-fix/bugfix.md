# Bugfix Requirements Document

## Introduction

The GitHub release pipeline fails during the `attach-javadocs` execution of `maven-javadoc-plugin`
3.6.3 because the plugin treats Javadoc warnings as errors by default. The affected source files
contain missing `@param`, `@return`, and class-level Javadoc comments, plus one broken `{@link}`
reference in `OverloadResolver`. These warnings cause the Javadoc JAR build to abort, blocking
every Maven Central release.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN the release pipeline runs `mvn -P release` THEN the system fails with
    `MavenReportException: Error while generating Javadoc` because `maven-javadoc-plugin` 3.6.3
    treats Javadoc warnings as errors.

1.2 WHEN `maven-javadoc-plugin` processes `Parameter.java` THEN the system emits
    `warning: no @param for name` and `warning: no @param for typeDescriptor` because the record
    compact constructor has no Javadoc `@param` tags.

1.3 WHEN `maven-javadoc-plugin` processes `AnnotatedMethod.java` THEN the system emits multiple
    `warning: no @param` warnings for the canonical and convenience constructors, and
    `warning: no comment` for the compact constructor, because those constructors lack complete
    Javadoc.

1.4 WHEN `maven-javadoc-plugin` processes `BytecodeInjector.java`, `ElementValidator.java`,
    `OverloadResolver.java`, and `RawitAnnotationProcessor.java` THEN the system emits
    `warning: use of default constructor, which does not provide a comment` because those classes
    have no explicit constructor with a Javadoc comment.

1.5 WHEN `maven-javadoc-plugin` processes `InvokerClassSpec.java` THEN the system emits
    `warning: no @return` for `build()` and `warning: no comment` for the constructor, because
    those members lack Javadoc.

1.6 WHEN `maven-javadoc-plugin` processes `JavaPoetGenerator.java` THEN the system emits
    `warning: no comment` for the constructor because it has no Javadoc.

1.7 WHEN `maven-javadoc-plugin` processes `MergeNode.java` THEN the system emits
    `warning: no comment` for the `BranchingNode` and `TerminalNode` compact constructors because
    those constructors lack Javadoc.

1.8 WHEN `maven-javadoc-plugin` processes `MergeTreeBuilder.java` THEN the system emits
    `warning: no comment` for the constructor because it has no Javadoc.

1.9 WHEN `maven-javadoc-plugin` processes `OverloadGroup.java` THEN the system emits
    `warning: no comment` for the compact constructor because it has no Javadoc.

1.10 WHEN `maven-javadoc-plugin` processes `OverloadResolver.java` THEN the system emits
     `error: reference not found` for the `{@link javax.tools.Filer#getResource}` tag because
     `Filer` does not declare a `getResource` method (it is declared on `JavaFileManager`).

1.11 WHEN `maven-javadoc-plugin` processes `StageInterfaceSpec.java` THEN the system emits
     `warning: no comment` for the constructor because it has no Javadoc.

1.12 WHEN `maven-javadoc-plugin` processes `TerminalInterfaceSpec.java` THEN the system emits
     `warning: no comment` for the constructor because it has no Javadoc.

1.13 WHEN `maven-javadoc-plugin` processes `ValidationResult.java` THEN the system emits
     `warning: no comment` for the `Valid` and `Invalid` compact constructors because those
     constructors lack Javadoc.

### Expected Behavior (Correct)

2.1 WHEN the release pipeline runs `mvn -P release` THEN the system SHALL complete the
    `attach-javadocs` goal without errors and produce a valid Javadoc JAR.

2.2 WHEN `maven-javadoc-plugin` processes `Parameter.java` THEN the system SHALL find `@param`
    tags for `name` and `typeDescriptor` in the record's Javadoc and emit no warnings.

2.3 WHEN `maven-javadoc-plugin` processes `AnnotatedMethod.java` THEN the system SHALL find
    complete `@param` documentation for all constructor parameters and a class-level comment for
    the compact constructor, and emit no warnings.

2.4 WHEN `maven-javadoc-plugin` processes `BytecodeInjector.java`, `ElementValidator.java`,
    `OverloadResolver.java`, and `RawitAnnotationProcessor.java` THEN the system SHALL find an
    explicit documented constructor (or a `@SuppressWarnings` / plugin configuration that silences
    the default-constructor warning) and emit no warnings.

2.5 WHEN `maven-javadoc-plugin` processes `InvokerClassSpec.java` THEN the system SHALL find a
    `@return` tag on `build()` and a Javadoc comment on the constructor, and emit no warnings.

2.6 WHEN `maven-javadoc-plugin` processes `JavaPoetGenerator.java` THEN the system SHALL find a
    Javadoc comment on the constructor and emit no warnings.

2.7 WHEN `maven-javadoc-plugin` processes `MergeNode.java` THEN the system SHALL find Javadoc
    comments on the `BranchingNode` and `TerminalNode` compact constructors and emit no warnings.

2.8 WHEN `maven-javadoc-plugin` processes `MergeTreeBuilder.java` THEN the system SHALL find a
    Javadoc comment on the constructor and emit no warnings.

2.9 WHEN `maven-javadoc-plugin` processes `OverloadGroup.java` THEN the system SHALL find a
    Javadoc comment on the compact constructor and emit no warnings.

2.10 WHEN `maven-javadoc-plugin` processes `OverloadResolver.java` THEN the system SHALL resolve
     the `{@link}` tag to `JavaFileManager#getResource` (the correct declaring type) and emit no
     errors or warnings.

2.11 WHEN `maven-javadoc-plugin` processes `StageInterfaceSpec.java` THEN the system SHALL find a
     Javadoc comment on the constructor and emit no warnings.

2.12 WHEN `maven-javadoc-plugin` processes `TerminalInterfaceSpec.java` THEN the system SHALL find
     a Javadoc comment on the constructor and emit no warnings.

2.13 WHEN `maven-javadoc-plugin` processes `ValidationResult.java` THEN the system SHALL find
     Javadoc comments on the `Valid` and `Invalid` compact constructors and emit no warnings.

### Unchanged Behavior (Regression Prevention)

3.1 WHEN the annotation processor runs during a normal (non-release) compile THEN the system SHALL
    CONTINUE TO process `@Invoker` and `@Constructor` annotations and generate staged API source
    files without any change in behavior.

3.2 WHEN the annotation processor validates an annotated element THEN the system SHALL CONTINUE TO
    emit the same `ERROR` diagnostics for invalid elements as before.

3.3 WHEN `MergeTreeBuilder` builds a merge tree for an overload group THEN the system SHALL
    CONTINUE TO produce the same `MergeTree` structure as before.

3.4 WHEN `BytecodeInjector` injects parameterless overloads into a `.class` file THEN the system
    SHALL CONTINUE TO produce bytecode-identical output as before.

3.5 WHEN `JavaPoetGenerator` generates source files for a merge tree THEN the system SHALL
    CONTINUE TO produce source-identical output as before.

3.6 WHEN all existing unit and property-based tests are run THEN the system SHALL CONTINUE TO pass
    without modification.
