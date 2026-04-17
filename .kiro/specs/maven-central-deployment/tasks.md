# Implementation Plan: Maven Central Deployment

## Overview

Coordinate update to publish Rawit under `io.github.ronnygunawan:rawit`.
Updates POM coordinates, description, Maven Central publishing configuration, and README snippets.

## Tasks

- [x] 1. Update pom.xml coordinates and description
  - Change `<groupId>` from `rg.rawit` to `io.github.ronnygunawan`
  - Change `<artifactId>` from `rg-rawit` to `rawit`
  - Change `<description>` to reference `@Invoker` and `@Constructor` instead of `@Curry`
  - _Requirements: 1.1, 1.2, 2.1, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

- [x] 2. Update README.md installation snippets and import examples
  - Replace the Maven `<dependency>` snippet: update `<groupId>` to `io.github.ronnygunawan` and `<artifactId>` to `rawit`; remove any reference to `rg.rawit` or `rg-rawit`
  - Add a Gradle Groovy DSL snippet with `annotationProcessor 'io.github.ronnygunawan:rawit:VERSION'` and `compileOnly 'io.github.ronnygunawan:rawit:VERSION'`
  - Add a Gradle Kotlin DSL snippet with `annotationProcessor("io.github.ronnygunawan:rawit:VERSION")` and `compileOnly("io.github.ronnygunawan:rawit:VERSION")`
  - Update the `import rawit.Invoker;` and `import rawit.Constructor;` examples
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 5.1, 5.2, 5.3, 5.4, 5.5_

- [x] 3. Write verification tests
  - [x] 3.1 Write PomCoordinatesTest
    - Parse `pom.xml` with `javax.xml.parsers.DocumentBuilder` and assert:
      - `groupId == "io.github.ronnygunawan"`
      - `artifactId == "rawit"`
      - `description` contains `"@Invoker"` and `"@Constructor"` and does not contain `"@Curry"`
      - Top-level elements `<name>`, `<url>`, `<licenses>`, `<developers>`, `<scm>` are present
      - Release profile contains `maven-source-plugin`, `maven-javadoc-plugin`, `maven-gpg-plugin`, `central-publishing-maven-plugin`
      - `autoPublish=true` implies `waitUntil=published`
    - _Requirements: 1.1, 1.2, 2.1, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

  - [x] 3.2 Write ReadmeSnippetsTest
    - Read `README.md` as a string and assert:
      - Contains `<groupId>io.github.ronnygunawan</groupId>`
      - Contains `<artifactId>rawit</artifactId>`
      - Does NOT contain `rg.rawit` or `rg-rawit`
      - Contains Groovy and Kotlin DSL Gradle snippets
      - Contains `import rawit.Invoker` and `import rawit.Constructor`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 5.1, 5.2, 5.3, 5.4_

  - [x] 3.3 Write ServiceRegistrationTest
    - Read `src/main/resources/META-INF/services/javax.annotation.processing.Processor` and assert trimmed content equals `rawit.processors.RawitAnnotationProcessor`

- [x] 4. Final checkpoint — ensure all tests pass
