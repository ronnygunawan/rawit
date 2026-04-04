# Implementation Plan: Maven Central Deployment

## Overview

Mechanical rename and coordinate update to publish Rawit under `io.github.ronnygunawan:rawit`.
All changes are co-dependent: POM coordinates, Java package rename, SPI registration, and README
snippets must be updated together so the project compiles and the processor is discoverable.

## Tasks

- [x] 1. Update pom.xml coordinates and description
  - Change `<groupId>` from `rg.rawit` to `io.github.ronnygunawan`
  - Change `<artifactId>` from `rg-rawit` to `rawit`
  - Change `<description>` to reference `@Invoker` and `@Constructor` instead of `@Curry`
  - _Requirements: 1.1, 1.2, 2.1, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

- [x] 2. Rename Java packages — main sources
  - [x] 2.1 Move annotation files and update package declarations
    - Move `src/main/java/rg/rawit/Invoker.java` → `src/main/java/rawit/Invoker.java`; update `package rg.rawit;` → `package rawit;`
    - Move `src/main/java/rg/rawit/Constructor.java` → `src/main/java/rawit/Constructor.java`; update `package rg.rawit;` → `package rawit;`
    - _Requirements: 6.1, 6.3_

  - [x] 2.2 Move model classes and update package/import declarations
    - Move all files under `src/main/java/rg/rawit/processors/model/` to `src/main/java/rawit/processors/model/`
    - Update `package rg.rawit.processors.model;` → `package rawit.processors.model;` in each file
    - _Requirements: 6.1, 6.3_

  - [x] 2.3 Move validation classes and update package/import declarations
    - Move all files under `src/main/java/rg/rawit/processors/validation/` to `src/main/java/rawit/processors/validation/`
    - Update `package rg.rawit.processors.validation;` → `package rawit.processors.validation;`
    - Update `import rg.rawit.Invoker;` and `import rg.rawit.Constructor;` in `ElementValidator.java` → `import rawit.Invoker;` / `import rawit.Constructor;`
    - _Requirements: 6.1, 6.3_

  - [x] 2.4 Move merge and inject classes and update package/import declarations
    - Move all files under `src/main/java/rg/rawit/processors/merge/` and `src/main/java/rg/rawit/processors/inject/` to the corresponding `rawit.*` paths
    - Update `package` declarations and all `import rg.rawit.*` statements in each file
    - _Requirements: 6.1, 6.3_

  - [x] 2.5 Move codegen classes and update package/import declarations
    - Move all files under `src/main/java/rg/rawit/processors/codegen/` to `src/main/java/rawit/processors/codegen/`
    - Update `package` declarations and all `import rg.rawit.*` statements
    - In `InvokerClassSpec.java`, update the `@Generated` value string:
      `"rg.rawit.processors.RawitAnnotationProcessor"` → `"rawit.processors.RawitAnnotationProcessor"`
    - _Requirements: 6.1, 6.3_

  - [x] 2.6 Move RawitAnnotationProcessor and update FQN string constants
    - Move `src/main/java/rg/rawit/processors/RawitAnnotationProcessor.java` → `src/main/java/rawit/processors/RawitAnnotationProcessor.java`
    - Update `package rg.rawit.processors;` → `package rawit.processors;`
    - Update all `import rg.rawit.*` statements
    - Update the two FQN constants:
      `INVOKER_ANNOTATION_FQN = "rg.rawit.Invoker"` → `"rawit.Invoker"`
      `CONSTRUCTOR_ANNOTATION_FQN = "rg.rawit.Constructor"` → `"rawit.Constructor"`
    - _Requirements: 6.1, 6.3_

- [x] 3. Rename Java packages — test sources
  - Move all files under `src/test/java/rg/rawit/` to `src/test/java/rawit/` (preserving sub-package structure)
  - Update `package rg.rawit.*` declarations and all `import rg.rawit.*` statements in every test file
  - _Requirements: 6.2, 6.3, 6.7_

- [x] 4. Update META-INF/services registration
  - Replace the content of `src/main/resources/META-INF/services/javax.annotation.processing.Processor`
    from `rg.rawit.processors.RawitAnnotationProcessor` to `rawit.processors.RawitAnnotationProcessor`
  - _Requirements: 6.4_

- [x] 5. Remove old rg/ source directories
  - Delete `src/main/java/rg/` and all its contents
  - Delete `src/test/java/rg/` and all its contents
  - _Requirements: 6.6_

- [x] 6. Update README.md installation snippets and import examples
  - Replace the Maven `<dependency>` snippet: update `<groupId>` to `io.github.ronnygunawan` and `<artifactId>` to `rawit`; remove any reference to `rg.rawit` or `rg-rawit`
  - Add a Gradle Groovy DSL snippet with `annotationProcessor 'io.github.ronnygunawan:rawit:VERSION'` and `compileOnly 'io.github.ronnygunawan:rawit:VERSION'`
  - Add a Gradle Kotlin DSL snippet with `annotationProcessor("io.github.ronnygunawan:rawit:VERSION")` and `compileOnly("io.github.ronnygunawan:rawit:VERSION")`
  - Update the `import rg.rawit.Invoker;` and `import rg.rawit.Constructor;` examples to `import rawit.Invoker;` and `import rawit.Constructor;`
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 5.1, 5.2, 5.3, 5.4, 5.5, 6.5_

- [x] 7. Checkpoint — verify the project compiles and all existing tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Write verification tests
  - [x] 8.1 Write PomCoordinatesTest
    - Create `src/test/java/rawit/PomCoordinatesTest.java`
    - Parse `pom.xml` with `javax.xml.parsers.DocumentBuilder` and assert:
      - `groupId == "io.github.ronnygunawan"`
      - `artifactId == "rawit"`
      - `description` contains `"@Invoker"` and `"@Constructor"` and does not contain `"@Curry"`
      - Top-level elements `<name>`, `<url>`, `<licenses>`, `<developers>`, `<scm>` are present
      - Release profile contains `maven-source-plugin`, `maven-javadoc-plugin`, `maven-gpg-plugin`, `central-publishing-maven-plugin`
      - `autoPublish=true` implies `waitUntil=published`
    - _Requirements: 1.1, 1.2, 2.1, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

  - [x] 8.2 Write ReadmeSnippetsTest
    - Create `src/test/java/rawit/ReadmeSnippetsTest.java`
    - Read `README.md` as a string and assert:
      - Contains `<groupId>io.github.ronnygunawan</groupId>`
      - Contains `<artifactId>rawit</artifactId>`
      - Does NOT contain `rg.rawit` or `rg-rawit`
      - Contains `annotationProcessor 'io.github.ronnygunawan:rawit:` and `compileOnly 'io.github.ronnygunawan:rawit:`
      - Contains `annotationProcessor("io.github.ronnygunawan:rawit:` and `compileOnly("io.github.ronnygunawan:rawit:`
      - Contains `import rawit.Invoker` and `import rawit.Constructor`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 5.1, 5.2, 5.3, 5.4, 6.5_

  - [x] 8.3 Write ServiceRegistrationTest
    - Create `src/test/java/rawit/ServiceRegistrationTest.java`
    - Read `src/main/resources/META-INF/services/javax.annotation.processing.Processor` and assert trimmed content equals `rawit.processors.RawitAnnotationProcessor`
    - _Requirements: 6.4_

  - [x] 8.4 Write OldDirectoriesRemovedTest
    - Create `src/test/java/rawit/OldDirectoriesRemovedTest.java`
    - Assert `src/main/java/rg/` does not exist as a directory
    - Assert `src/test/java/rg/` does not exist as a directory
    - _Requirements: 6.6_

  - [x] 8.5 Write SourceFilePackageConsistencyTest (property-based)
    - **Property 1: Source file package consistency**
    - **Validates: Requirements 6.1, 6.2, 6.3**
    - Create `src/test/java/rawit/SourceFilePackageConsistencyTest.java` using jqwik
    - Enumerate all `.java` files under `src/main/java/` and `src/test/java/` via `Files.walk`
    - For each file assert:
      1. The file path does not contain `rg/rawit` or `rg\rawit`
      2. The `package` declaration in the file matches the directory path relative to the source root

  - [x] 8.6 Write NoStaleRgRawitReferencesTest (property-based)
    - **Property 2: No stale rg.rawit references in source files**
    - **Validates: Requirements 6.3**
    - Create `src/test/java/rawit/NoStaleRgRawitReferencesTest.java` using jqwik
    - Enumerate all `.java` files under `src/` via `Files.walk`
    - For each file assert the file content does not contain the string `rg.rawit`

- [x] 9. Final checkpoint — ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Tasks 2 and 3 must be completed before task 4 (the SPI file references the new FQN)
- Task 5 (directory removal) must come after tasks 2 and 3 are fully complete
- The two FQN string constants in `RawitAnnotationProcessor` (task 2.6) are critical — if missed, the processor will compile but silently process nothing
- The `@Generated` value in `InvokerClassSpec` (task 2.5) affects generated source output; stale value won't break compilation but is misleading
