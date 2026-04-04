# Requirements Document

## Introduction

This feature covers the changes needed to publish the Rawit annotation processor to Maven Central
under the verified namespace `io.github.ronnygunawan`. It includes correcting the `groupId` and
`artifactId` in `pom.xml`, renaming all Java packages from `rg.rawit` to `rawit` (and moving the
corresponding source directories), fixing the project description, ensuring all Maven Central
publishing configuration is correct, and updating the README installation instructions to reflect
the new coordinates for both Maven and Gradle users.

## Glossary

- **POM**: The `pom.xml` Project Object Model file that defines Maven build configuration.
- **groupId**: The Maven coordinate component identifying the namespace owner (`io.github.ronnygunawan`).
- **artifactId**: The Maven coordinate component identifying the artifact (`rawit`).
- **Maven_Central**: The public Maven repository at `repo1.maven.org` managed via Sonatype Central Portal.
- **README**: The `README.md` file at the repository root containing user-facing documentation.
- **Release_Profile**: The `<profile id="release">` block in `pom.xml` that activates publishing plugins.
- **Gradle_Snippet**: The Gradle dependency block shown in the README for Gradle users.
- **Maven_Snippet**: The Maven `<dependency>` block shown in the README for Maven users.
- **Package_Rename**: Moving all Java source files from the `rg.rawit.*` package hierarchy to `rawit.*`, including updating `package` declarations, `import` statements, directory structure, and the `META-INF/services` processor registration file.

## Requirements

### Requirement 1: Correct groupId and artifactId

**User Story:** As a library maintainer, I want the Maven coordinates to match my verified
Maven Central namespace, so that the artifact can be published and resolved correctly.

#### Acceptance Criteria

1. THE POM SHALL declare `<groupId>io.github.ronnygunawan</groupId>`.
2. THE POM SHALL declare `<artifactId>rawit</artifactId>`.
3. THE POM SHALL declare a `<version>` that does not end with `-SNAPSHOT` at the time of release.

### Requirement 2: Correct project description

**User Story:** As a library maintainer, I want the POM description to accurately reflect the
current annotation names, so that Maven Central metadata is not misleading.

#### Acceptance Criteria

1. THE POM SHALL declare a `<description>` that references `@Invoker` and `@Constructor` instead of `@Curry` and `@Constructor`.

### Requirement 3: Complete Maven Central publishing configuration

**User Story:** As a library maintainer, I want all required Maven Central metadata and plugins
configured in `pom.xml`, so that `mvn deploy -P release` succeeds without manual intervention.

#### Acceptance Criteria

1. THE POM SHALL declare `<name>`, `<description>`, `<url>`, `<licenses>`, `<developers>`, and `<scm>` elements at the top level.
2. THE Release_Profile SHALL include the `maven-source-plugin` configured to attach a sources JAR.
3. THE Release_Profile SHALL include the `maven-javadoc-plugin` configured to attach a Javadoc JAR.
4. THE Release_Profile SHALL include the `maven-gpg-plugin` configured to sign all artifacts during the `verify` phase.
5. THE Release_Profile SHALL include the `central-publishing-maven-plugin` configured with `<publishingServerId>central</publishingServerId>`.
6. WHEN `autoPublish` is set to `true` in the `central-publishing-maven-plugin` configuration, THE Release_Profile SHALL also set `<waitUntil>published</waitUntil>`.

### Requirement 4: Update README Maven installation snippet

**User Story:** As a Maven user, I want the README to show the correct Maven dependency coordinates,
so that I can add Rawit to my project without guessing the right groupId or artifactId.

#### Acceptance Criteria

1. THE README SHALL contain a Maven `<dependency>` snippet with `<groupId>io.github.ronnygunawan</groupId>`.
2. THE README SHALL contain a Maven `<dependency>` snippet with `<artifactId>rawit</artifactId>`.
3. THE README SHALL NOT contain any Maven snippet referencing the old `groupId` value `rg.rawit`.
4. THE README SHALL NOT contain any Maven snippet referencing the old `artifactId` value `rg-rawit`.

### Requirement 5: Add README Gradle installation snippet

**User Story:** As a Gradle user, I want the README to show Gradle dependency coordinates for both
Groovy DSL and Kotlin DSL, so that I can add Rawit to my project without manual coordinate lookup.

#### Acceptance Criteria

1. THE README SHALL contain a Groovy DSL Gradle snippet with `annotationProcessor 'io.github.ronnygunawan:rawit:VERSION'`.
2. THE README SHALL contain a Groovy DSL Gradle snippet with `compileOnly 'io.github.ronnygunawan:rawit:VERSION'`.
3. THE README SHALL contain a Kotlin DSL Gradle snippet with `annotationProcessor("io.github.ronnygunawan:rawit:VERSION")`.
4. THE README SHALL contain a Kotlin DSL Gradle snippet with `compileOnly("io.github.ronnygunawan:rawit:VERSION")`.
5. WHEN a `VERSION` placeholder is used in the README Gradle snippets, THE README SHALL display the placeholder in a way that makes it clear the user must substitute the actual release version.

### Requirement 6: Rename Java packages from rg.rawit to rawit

**User Story:** As a library maintainer, I want the Java package names to be clean and consistent
with the project identity, so that users importing the library see `rawit.*` rather than `rg.rawit.*`.

#### Acceptance Criteria

1. ALL source files under `src/main/java/rg/rawit/` SHALL be moved to `src/main/java/rawit/` with their `package` declarations updated from `rg.rawit.*` to `rawit.*`.
2. ALL source files under `src/test/java/rg/rawit/` SHALL be moved to `src/test/java/rawit/` with their `package` declarations updated from `rg.rawit.*` to `rawit.*`.
3. ALL `import` statements referencing `rg.rawit.*` in any source file SHALL be updated to reference `rawit.*`.
4. THE `META-INF/services/javax.annotation.processing.Processor` file SHALL be updated to reference the new fully-qualified class name `rawit.processors.RawitAnnotationProcessor`.
5. THE README SHALL update any `import` examples that reference `rg.rawit.Invoker` or `rg.rawit.Constructor` to use `rawit.Invoker` and `rawit.Constructor`.
6. THE old source directories `src/main/java/rg/` and `src/test/java/rg/` SHALL be removed after all files are moved.
7. ALL existing unit and property-based tests SHALL continue to pass after the rename.
