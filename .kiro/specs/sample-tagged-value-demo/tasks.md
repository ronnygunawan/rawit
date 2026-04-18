# Implementation Plan: Sample Tagged Value Demo

## Overview

Update both Maven and Gradle sample projects to demonstrate the `@TaggedValue` annotation with rawit `1.1.0-rc-1`. Changes include version bumps in build files, five new Java source files in `com.example.model`, and a new JUnit 5 test file. All new Java files must be identical between both samples. Existing files remain unmodified.

## Tasks

- [x] 1. Update build configuration files to rawit 1.1.0-rc-1
  - [x] 1.1 Update Maven sample `pom.xml` version
    - In `samples/maven-sample/pom.xml`, change the rawit dependency version from `1.0.0` to `1.1.0-rc-1`
    - Do not modify any other dependency or plugin version
    - _Requirements: 1.1_
  - [x] 1.2 Update Gradle sample `build.gradle` version
    - In `samples/gradle-sample/build.gradle`, change both the `annotationProcessor` and `compileOnly` rawit dependency versions from `1.0.0` to `1.1.0-rc-1`
    - Do not modify any other dependency version
    - _Requirements: 1.2_

- [x] 2. Create tag annotation declarations in Maven sample
  - [x] 2.1 Create `UserId.java` tag annotation
    - Create `samples/maven-sample/src/main/java/com/example/model/UserId.java`
    - Declare `@interface UserId` annotated with `@TaggedValue(strict = true)` in package `com.example.model`
    - Include a Javadoc comment explaining that strict mode warns on tagged↔untagged assignments (except literals/constants)
    - _Requirements: 2.1, 2.5_
  - [x] 2.2 Create `FirstName.java` tag annotation
    - Create `samples/maven-sample/src/main/java/com/example/model/FirstName.java`
    - Declare `@interface FirstName` annotated with `@TaggedValue` (lax mode, no `strict` attribute) in package `com.example.model`
    - Include a Javadoc comment explaining that lax mode only warns on tag mismatches between different tags
    - _Requirements: 2.2, 2.6_
  - [x] 2.3 Create `LastName.java` tag annotation
    - Create `samples/maven-sample/src/main/java/com/example/model/LastName.java`
    - Declare `@interface LastName` annotated with `@TaggedValue` (lax mode, no `strict` attribute) in package `com.example.model`
    - Include a Javadoc comment explaining that lax mode only warns on tag mismatches between different tags
    - _Requirements: 2.3, 2.6_

- [x] 3. Create tagged model classes in Maven sample
  - [x] 3.1 Create `TaggedUser.java` record
    - Create `samples/maven-sample/src/main/java/com/example/model/TaggedUser.java`
    - Declare `@Constructor public record TaggedUser(@UserId long userId, @FirstName String firstName, @LastName String lastName)`
    - Include a Javadoc comment explaining that tag annotations propagate onto the generated builder chain stage method parameters
    - _Requirements: 3.1, 3.2, 3.4_
  - [x] 3.2 Create `UserService.java` class
    - Create `samples/maven-sample/src/main/java/com/example/model/UserService.java`
    - Declare class with `@Getter @FirstName private String currentFirstName` and `@Getter @LastName private String currentLastName` fields
    - Declare `@Invoker public String formatName(@FirstName String firstName, @LastName String lastName)` method that stores params in fields and returns `firstName + " " + lastName`
    - _Requirements: 4.1, 4.2, 5.1_

- [x] 4. Copy new source files to Gradle sample
  - [x] 4.1 Copy all five new Java source files to Gradle sample
    - Copy `UserId.java`, `FirstName.java`, `LastName.java`, `TaggedUser.java`, and `UserService.java` from `samples/maven-sample/src/main/java/com/example/model/` to `samples/gradle-sample/src/main/java/com/example/model/`
    - Files must be byte-for-byte identical between both samples
    - _Requirements: 2.4, 3.3, 4.3, 5.2, 12.1, 12.2_

- [x] 5. Checkpoint - Verify source files
  - Ensure all new source files exist in both samples and are identical. Ensure existing files (`Calculator.java`, `Point.java`, `Coord.java`, `User.java`) are unmodified. Ask the user if questions arise.

- [x] 6. Create JUnit 5 test file for tagged value demonstrations
  - [x] 6.1 Create `TaggedValueSampleTest.java` in Maven sample
    - Create `samples/maven-sample/src/test/java/com/example/TaggedValueSampleTest.java`
    - Implement `taggedConstructorWithLiterals()` test: construct `TaggedUser` via staged chain with literal values (42, "John", "Doe"), assert all three accessors return expected values
    - Implement `taggedConstructorWithTaggedVariables()` test: declare `@UserId long id = 99`, `@FirstName String first = "Jane"`, `@LastName String last = "Smith"`, construct `TaggedUser` via staged chain, assert all three accessors
    - Implement `taggedInvokerChain()` test: create `UserService`, declare `@FirstName` and `@LastName` tagged variables, invoke `formatName()` via staged chain, assert result equals concatenated name
    - Implement `taggedGetterOnFields()` test: create `UserService`, invoke `formatName()` via staged chain, assert `getCurrentFirstName()` and `getCurrentLastName()` return expected values
    - Implement `taggedRecordAccessors()` test: construct `TaggedUser` via staged chain, assert `userId()`, `firstName()`, `lastName()` return expected values
    - _Requirements: 6.1, 6.2, 7.1, 7.2, 8.1, 8.2, 9.1, 10.1_
  - [x] 6.2 Copy test file to Gradle sample
    - Copy `TaggedValueSampleTest.java` from `samples/maven-sample/src/test/java/com/example/` to `samples/gradle-sample/src/test/java/com/example/`
    - File must be byte-for-byte identical
    - _Requirements: 6.3, 7.3, 8.3, 9.2, 10.2, 12.1, 12.2_

- [x] 7. Verify existing files are preserved
  - [x] 7.1 Verify existing model classes are unmodified
    - Confirm `Calculator.java`, `Point.java`, `Coord.java`, and `User.java` in both samples are unchanged from their current content
    - Confirm `RawitSampleTest.java` in both samples is unchanged
    - _Requirements: 11.1, 11.2, 11.3_

- [x] 8. Write property tests for correctness properties
  - [x] 8.1 Write property test for source file identity and completeness
    - **Property 1: Source file identity and completeness across samples**
    - Create a jqwik property test that enumerates all `.java` files under `samples/maven-sample/src/`, verifies each has an identical counterpart under `samples/gradle-sample/src/`, and vice versa — asserting byte-for-byte identity and equal file sets
    - **Validates: Requirements 2.4, 3.3, 4.3, 5.2, 6.3, 7.3, 8.3, 9.2, 10.2, 11.3, 12.1, 12.2**
  - [x] 8.2 Write property test for build file version correctness
    - **Property 2: Build files reference correct rawit version**
    - Create a jqwik property test that for each build file (`pom.xml`, `build.gradle`) verifies it contains the version string `1.1.0-rc-1` and does not reference `1.0.0` as a rawit dependency version
    - **Validates: Requirements 1.1, 1.2**

- [x] 9. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- All new Java source files are created first in the Maven sample, then copied verbatim to the Gradle sample to guarantee byte-for-byte identity
- Existing files (`Calculator.java`, `Point.java`, `Coord.java`, `User.java`, `RawitSampleTest.java`) must not be modified
- The design specifies exact file content for all new source files — refer to the design document during implementation
- Property tests use jqwik (already available in the parent project)
- Integration testing (running `mvn clean test` / `gradle clean test`) is manual/CI and not included as automated tasks
