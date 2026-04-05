# Requirements Document

## Introduction

Rawit is currently compiled and tested against Java 21. This feature lowers the minimum supported
Java version to Java 17, so that projects still on Java 17 LTS can use Rawit as an annotation
processor without upgrading their JDK.

The change has three dimensions:

1. **Build configuration** — lower the Maven `source`/`target` (or `release`) level from 21 to 17
   so the processor jar is loadable on a Java 17 JVM.
2. **Source compatibility** — replace Java 21-only language features (pattern matching in `switch`
   expressions, guarded patterns) with Java 17-compatible equivalents (`instanceof` pattern
   variables introduced in Java 16 are fine; `switch` pattern matching is not).
3. **Documentation** — update the README to reflect Java 17 as the new minimum requirement.

The processor must continue to work correctly when used by a project compiled at any source level
from Java 17 through the latest supported version.

## Glossary

- **Processor**: The `RawitAnnotationProcessor` and all supporting classes in the `rawit.processors`
  package tree that together form the annotation processor jar.
- **Host_JVM**: The Java virtual machine that runs `javac` (and therefore the Processor) during a
  user's build. This is the JVM whose version determines whether the Processor jar can be loaded.
- **User_Project**: A Maven or Gradle project that declares Rawit as an annotation processor
  dependency and compiles its own sources at some Java source level (17–latest).
- **Bytecode_Version**: The `major_version` field in a `.class` file header. Java 17 class files
  have major version 61; Java 21 class files have major version 65.
- **Release_Flag**: The Maven Compiler Plugin `<release>` (or `<source>`/`<target>`) configuration
  that controls the bytecode version emitted for the Processor's own `.class` files.
- **Pattern_Switch**: A Java 21 language feature (`switch (x) { case Foo f -> ... }`) that is not
  available under `--release 17`.
- **ASM**: The bytecode manipulation library (OW2 ASM) used by `BytecodeInjector` to read and
  rewrite user `.class` files.

---

## Requirements

### Requirement 1: Processor Jar Loadable on Java 17

**User Story:** As a developer on a Java 17 project, I want to add Rawit as an annotation processor
dependency and have it work without upgrading my JDK, so that I can adopt staged invocation without
a Java version migration.

#### Acceptance Criteria

1. THE Processor SHALL be compiled with `--release 17` (or equivalent `<source>17</source>` /
   `<target>17</target>`) so that all `.class` files in the Processor jar have a Bytecode_Version
   of at most 61 (Java 17).
2. WHEN the Processor jar is loaded by a Host_JVM running Java 17, THE Host_JVM SHALL load the
   Processor without throwing `UnsupportedClassVersionError`.
3. THE Maven `pom.xml` `java.version` property SHALL be set to `17`.

---

### Requirement 2: No Java 21-Only Language Features in Processor Source

**User Story:** As a contributor, I want the Processor source code to compile cleanly under
`--release 17`, so that the Java 17 compatibility guarantee is enforced at build time and does not
silently regress.

#### Acceptance Criteria

1. THE Processor source code SHALL NOT use Pattern_Switch (`switch` expressions or statements with
   type-pattern cases), which require `--release 21`.
2. WHEN the Processor source is compiled with `--release 17`, THE Maven Compiler Plugin SHALL
   report zero errors and zero warnings related to unsupported language features.
3. THE Processor MAY use `instanceof` pattern variables (e.g. `if (x instanceof Foo f)`) because
   that feature is available since Java 16 and is within the `--release 17` language level.
4. THE Processor MAY use `sealed` classes and interfaces if they are already present, because
   sealed types are available since Java 17.

---

### Requirement 3: Processor Correctly Handles Java 17 User Class Files

**User Story:** As a developer on a Java 17 project, I want the bytecode injector to read and
rewrite my Java 17 `.class` files without errors, so that the staged invocation entry points are
injected correctly regardless of my project's Java version.

#### Acceptance Criteria

1. WHEN the Processor's `BytecodeInjector` reads a `.class` file with Bytecode_Version 61
   (Java 17), THE `BytecodeInjector` SHALL parse it without error using ASM.
2. WHEN the Processor injects a parameterless overload into a Java 17 `.class` file, THE
   `BytecodeInjector` SHALL write back a `.class` file that passes ASM's `CheckClassAdapter`
   verification.
3. THE `BytecodeInjector` SHALL preserve the original Bytecode_Version of the user's `.class`
   file; it SHALL NOT upgrade the version to Java 21 or any other version during injection.

---

### Requirement 4: Processor Accepts Java 17 Source in User Projects

**User Story:** As a developer on a Java 17 project, I want the annotation processor to run
without errors when `javac` compiles my Java 17 source files, so that I get the same compile-time
validation and code generation as Java 21 users.

#### Acceptance Criteria

1. WHEN `javac` invokes the Processor with `--release 17` (or `--source 17`) on a User_Project,
   THE Processor SHALL complete annotation processing without emitting any `Diagnostic.Kind.ERROR`
   diagnostics caused by the Java version.
2. THE Processor's `getSupportedSourceVersion()` SHALL return `SourceVersion.latestSupported()` so
   that `javac` does not emit a warning about the processor not supporting the current source
   version.
3. WHEN the Processor generates source files via JavaPoet for a User_Project compiled at Java 17,
   THE generated source files SHALL compile without errors under `--release 17`.

---

### Requirement 5: README Documents Java 17 as Minimum Supported Version

**User Story:** As a developer evaluating Rawit, I want the README to clearly state the minimum
Java version required, so that I know whether Rawit is compatible with my project before adding
the dependency.

#### Acceptance Criteria

1. THE `README.md` SHALL state that Rawit requires Java 17 or later (replacing the current
   "Java 21" statement).
2. THE `README.md` "Building from Source" section SHALL state that building from source requires
   Java 17 and Maven 3.8+.
3. THE `README.md` SHALL NOT contain any reference to Java 21 as a minimum requirement.

---

### Requirement 6: Existing Tests Pass Under Java 17 Compilation Target

**User Story:** As a contributor, I want all existing unit and property-based tests to continue
passing after the Java 17 compatibility change, so that no existing behaviour is regressed.

#### Acceptance Criteria

1. WHEN `mvn test` is run with the updated `pom.xml` targeting Java 17, ALL existing tests SHALL
   pass without modification.
2. THE integration tests in `RawitAnnotationProcessorIntegrationTest` SHALL continue to compile
   and run correctly, exercising the full two-pass compile and bytecode injection pipeline.
3. FOR ALL property-based tests (jqwik), the properties SHALL continue to hold after the source
   compatibility change.
