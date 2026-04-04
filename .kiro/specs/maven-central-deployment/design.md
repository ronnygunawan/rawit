# Design Document: Maven Central Deployment

## Overview

This feature prepares the Rawit annotation processor for publication to Maven Central under the
verified namespace `io.github.ronnygunawan`. The changes are purely mechanical — no runtime
behaviour changes — but they touch several layers of the project simultaneously:

1. **POM coordinates and metadata** — `groupId`, `artifactId`, `description`, and the release
   profile publishing plugins.
2. **Java package rename** — every source file moves from `rg.rawit.*` to `rawit.*`, including
   directory structure, `package` declarations, `import` statements, and the
   `META-INF/services` processor registration.
3. **README documentation** — Maven and Gradle installation snippets updated to the new
   coordinates; import examples updated to the new package names.

Because the changes are co-dependent (the POM `groupId` must match the package namespace
convention, and the README must reflect both), all three areas are treated as a single atomic
feature.

---

## Architecture

The project is a standard Maven single-module annotation processor. There is no runtime
dependency on the library itself — consumers add it as an `annotationProcessor` / `compileOnly`
dependency. The architecture is unchanged by this feature; only coordinates and package names
are updated.

```
pom.xml                          ← groupId / artifactId / description / release profile
src/main/java/rawit/             ← renamed from rg/rawit/
  Invoker.java
  Constructor.java
  processors/
    RawitAnnotationProcessor.java
    codegen/  inject/  merge/  model/  validation/
src/test/java/rawit/             ← renamed from rg/rawit/
  processors/
    ...
src/main/resources/META-INF/services/
  javax.annotation.processing.Processor   ← updated FQN
README.md                        ← updated snippets
```

The annotation processor is registered via the standard Java SPI mechanism
(`META-INF/services/javax.annotation.processing.Processor`). After the rename the registered
class name must be `rawit.processors.RawitAnnotationProcessor`.

---

## Components and Interfaces

### 1. pom.xml

**Current state:**
```xml
<groupId>rg.rawit</groupId>
<artifactId>rg-rawit</artifactId>
<description>Compile-time staged invocation API via @Curry and @Constructor annotation processors</description>
```

**Target state:**
```xml
<groupId>io.github.ronnygunawan</groupId>
<artifactId>rawit</artifactId>
<description>Compile-time staged invocation API via @Invoker and @Constructor annotation processors</description>
```

The release profile already contains `maven-source-plugin`, `maven-javadoc-plugin`,
`maven-gpg-plugin`, and `central-publishing-maven-plugin` with `autoPublish=true` and
`waitUntil=published`. No structural changes to the release profile are needed beyond
verifying all required elements are present.

The top-level metadata elements (`<name>`, `<description>`, `<url>`, `<licenses>`,
`<developers>`, `<scm>`) are already present and correct except for `<description>`.

### 2. Java Package Rename

All Java source files currently live under `src/main/java/rg/rawit/` and
`src/test/java/rg/rawit/`. They must be moved to `src/main/java/rawit/` and
`src/test/java/rawit/` respectively.

**Files to move (main):**

| Old path | New path |
|---|---|
| `src/main/java/rg/rawit/Invoker.java` | `src/main/java/rawit/Invoker.java` |
| `src/main/java/rg/rawit/Constructor.java` | `src/main/java/rawit/Constructor.java` |
| `src/main/java/rg/rawit/processors/RawitAnnotationProcessor.java` | `src/main/java/rawit/processors/RawitAnnotationProcessor.java` |
| `src/main/java/rg/rawit/processors/codegen/InvokerClassSpec.java` | `src/main/java/rawit/processors/codegen/InvokerClassSpec.java` |
| `src/main/java/rg/rawit/processors/codegen/JavaPoetGenerator.java` | `src/main/java/rawit/processors/codegen/JavaPoetGenerator.java` |
| `src/main/java/rg/rawit/processors/codegen/StageInterfaceSpec.java` | `src/main/java/rawit/processors/codegen/StageInterfaceSpec.java` |
| `src/main/java/rg/rawit/processors/codegen/TerminalInterfaceSpec.java` | `src/main/java/rawit/processors/codegen/TerminalInterfaceSpec.java` |
| `src/main/java/rg/rawit/processors/inject/BytecodeInjector.java` | `src/main/java/rawit/processors/inject/BytecodeInjector.java` |
| `src/main/java/rg/rawit/processors/inject/OverloadResolver.java` | `src/main/java/rawit/processors/inject/OverloadResolver.java` |
| `src/main/java/rg/rawit/processors/merge/MergeTreeBuilder.java` | `src/main/java/rawit/processors/merge/MergeTreeBuilder.java` |
| `src/main/java/rg/rawit/processors/model/AnnotatedMethod.java` | `src/main/java/rawit/processors/model/AnnotatedMethod.java` |
| `src/main/java/rg/rawit/processors/model/MergeNode.java` | `src/main/java/rawit/processors/model/MergeNode.java` |
| `src/main/java/rg/rawit/processors/model/MergeTree.java` | `src/main/java/rawit/processors/model/MergeTree.java` |
| `src/main/java/rg/rawit/processors/model/OverloadGroup.java` | `src/main/java/rawit/processors/model/OverloadGroup.java` |
| `src/main/java/rg/rawit/processors/model/Parameter.java` | `src/main/java/rawit/processors/model/Parameter.java` |
| `src/main/java/rg/rawit/processors/validation/ElementValidator.java` | `src/main/java/rawit/processors/validation/ElementValidator.java` |
| `src/main/java/rg/rawit/processors/validation/ValidationResult.java` | `src/main/java/rawit/processors/validation/ValidationResult.java` |

The same mapping applies to all test files under `src/test/java/rg/rawit/`.

**Per-file changes required:**
- `package rg.rawit` → `package rawit`
- `package rg.rawit.processors` → `package rawit.processors`
- `package rg.rawit.processors.*` → `package rawit.processors.*`
- All `import rg.rawit.*` → `import rawit.*`
- The hardcoded FQN strings inside `RawitAnnotationProcessor.java`:
  ```java
  // Before
  private static final String INVOKER_ANNOTATION_FQN = "rg.rawit.Invoker";
  private static final String CONSTRUCTOR_ANNOTATION_FQN = "rg.rawit.Constructor";
  // After
  private static final String INVOKER_ANNOTATION_FQN = "rawit.Invoker";
  private static final String CONSTRUCTOR_ANNOTATION_FQN = "rawit.Constructor";
  ```
- The `@Generated` annotation value in `InvokerClassSpec.java`:
  ```java
  // Before
  .addMember("value", "$S", "rg.rawit.processors.RawitAnnotationProcessor")
  // After
  .addMember("value", "$S", "rawit.processors.RawitAnnotationProcessor")
  ```

### 3. META-INF/services Registration

```
# Before
rg.rawit.processors.RawitAnnotationProcessor

# After
rawit.processors.RawitAnnotationProcessor
```

### 4. README.md

**Maven snippet** (Quick Start → Add the dependency):

```xml
<!-- Before -->
<dependency>
    <groupId>rg.rawit</groupId>
    <artifactId>rg-rawit</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>

<!-- After -->
<dependency>
    <groupId>io.github.ronnygunawan</groupId>
    <artifactId>rawit</artifactId>
    <version>VERSION</version>
</dependency>
```

**Gradle snippets** (new section, added after the Maven snippet):

```groovy
// Groovy DSL
dependencies {
    annotationProcessor 'io.github.ronnygunawan:rawit:VERSION'
    compileOnly 'io.github.ronnygunawan:rawit:VERSION'
}
```

```kotlin
// Kotlin DSL
dependencies {
    annotationProcessor("io.github.ronnygunawan:rawit:VERSION")
    compileOnly("io.github.ronnygunawan:rawit:VERSION")
}
```

**Import examples** in the Quick Start code block:

```java
// Before
import rg.rawit.Invoker;
import rg.rawit.Constructor;

// After
import rawit.Invoker;
import rawit.Constructor;
```

---

## Data Models

No new data models are introduced. The existing models (`AnnotatedMethod`, `MergeTree`,
`MergeNode`, `OverloadGroup`, `Parameter`, `ValidationResult`) are unchanged in structure;
only their package declarations are updated.

The only data-level change is the two FQN string constants in `RawitAnnotationProcessor`:

```java
// rawit.processors.RawitAnnotationProcessor
private static final String INVOKER_ANNOTATION_FQN    = "rawit.Invoker";
private static final String CONSTRUCTOR_ANNOTATION_FQN = "rawit.Constructor";
```

These strings are used to filter annotation types during processing. If they do not match the
actual annotation FQNs after the rename, the processor will silently process nothing.

---

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*


### Property 1: Source file package consistency

*For any* `.java` file found anywhere under `src/main/java/` or `src/test/java/`, the file's
`package` declaration must match its directory path relative to the respective source root, and
no `.java` file shall reside under a path containing `rg/rawit`.

**Validates: Requirements 6.1, 6.2, 6.3**

### Property 2: No stale `rg.rawit` references in source files

*For any* `.java` file found anywhere under `src/`, the file shall contain no occurrence of the
string `rg.rawit` (neither as a `package` declaration nor as an `import` statement nor as a
string literal).

**Validates: Requirements 6.3**

---

## Error Handling

### POM coordinate errors

If the `groupId` or `artifactId` is wrong at publish time, Sonatype Central Portal will reject
the upload with a validation error. The fix is purely editorial — update the values in `pom.xml`.

### Package rename errors

The most likely failure mode is a missed reference: a file whose `package` declaration or
`import` statement still references `rg.rawit`. This will cause a compile error (`package
rg.rawit does not exist`) that is immediately visible on `mvn compile`. The test suite
(`mvn test`) will catch any remaining references.

A second failure mode is the `META-INF/services` file not being updated. In that case the
processor will not be discovered by `javac` at all — no code generation will occur, and the
integration tests will fail with missing generated classes.

A third failure mode is the FQN string constants in `RawitAnnotationProcessor` not being
updated. The processor will compile and be discovered, but it will not match any annotation
types and will silently do nothing. The integration tests will catch this.

### README errors

Stale coordinates in the README do not cause build failures but will mislead users. The
acceptance criteria tests (see Testing Strategy) catch these at review time.

---

## Testing Strategy

### Dual Testing Approach

Both unit/example tests and property-based tests are used:

- **Example tests** verify specific, known facts about specific files (pom.xml, README.md,
  META-INF/services). These are straightforward assertions on file content.
- **Property tests** verify universal invariants that must hold across all source files in the
  project (package consistency, absence of stale references).

### Example Tests

These are written as JUnit 5 tests and verify the acceptance criteria that are specific to
known files:

**PomCoordinatesTest**
- Parses `pom.xml` and asserts `groupId == "io.github.ronnygunawan"`.
- Asserts `artifactId == "rawit"`.
- Asserts `description` contains `"@Invoker"` and `"@Constructor"` and does not contain `"@Curry"`.
- Asserts top-level elements `<name>`, `<url>`, `<licenses>`, `<developers>`, `<scm>` are present.
- Asserts the release profile contains `maven-source-plugin`, `maven-javadoc-plugin`,
  `maven-gpg-plugin`, and `central-publishing-maven-plugin`.
- Asserts `autoPublish=true` implies `waitUntil=published`.

**ReadmeSnippetsTest**
- Reads `README.md` and asserts it contains `<groupId>io.github.ronnygunawan</groupId>`.
- Asserts it contains `<artifactId>rawit</artifactId>`.
- Asserts it does NOT contain `rg.rawit` or `rg-rawit`.
- Asserts it contains the Groovy DSL lines `annotationProcessor 'io.github.ronnygunawan:rawit:` and `compileOnly 'io.github.ronnygunawan:rawit:`.
- Asserts it contains the Kotlin DSL lines `annotationProcessor("io.github.ronnygunawan:rawit:` and `compileOnly("io.github.ronnygunawan:rawit:`.
- Asserts it contains `import rawit.Invoker` and `import rawit.Constructor`.

**ServiceRegistrationTest**
- Reads `src/main/resources/META-INF/services/javax.annotation.processing.Processor` and
  asserts its trimmed content equals `rawit.processors.RawitAnnotationProcessor`.

**OldDirectoriesRemovedTest**
- Asserts that `src/main/java/rg/` does not exist.
- Asserts that `src/test/java/rg/` does not exist.

### Property-Based Tests

These use **jqwik** (already a test dependency) and verify universal invariants over all source
files. Because the "inputs" are the actual files on disk rather than randomly generated values,
these are implemented as parameterized property tests that enumerate all `.java` files.

**SourceFilePackageConsistencyTest**
- Tag: `Feature: maven-central-deployment, Property 1: Source file package consistency`
- Enumerates all `.java` files under `src/main/java/` and `src/test/java/`.
- For each file, asserts:
  1. The file path does not contain `rg/rawit` or `rg\rawit`.
  2. The `package` declaration in the file matches the directory path relative to the source root.
- Minimum 1 iteration per file (deterministic enumeration, not random).

**NoStaleRgRawitReferencesTest**
- Tag: `Feature: maven-central-deployment, Property 2: No stale rg.rawit references`
- Enumerates all `.java` files under `src/`.
- For each file, asserts the file content does not contain the string `rg.rawit`.
- Minimum 1 iteration per file.

### Build-Level Verification

The ultimate correctness check is `mvn test` passing cleanly after all changes. The existing
unit and property-based test suite (jqwik + JUnit 5) exercises the annotation processor's
logic end-to-end and will fail immediately if any package reference is broken.
