# Implementation Plan: @TaggedValue Annotation

## Overview

Implement the `@TaggedValue` meta-annotation and compile-time tagged value analyzer for the rawit annotation processor. The pipeline follows: meta-annotation definition → model records (`TagInfo`, `TagResolution`, `AssignmentWarning`) → pure logic components (`TagDiscoverer`, `TagResolver`, `AssignmentChecker`) → AST scanner (`TaggedValueAnalyzer`) → processor integration → tag annotation propagation in generated code. Each component is built incrementally with property-based tests (jqwik) validating correctness properties from the design document. The `AssignmentChecker` is the core pure-function component covering the full warning decision matrix. Tasks 13–15 extend the code generation pipeline to propagate tag annotations onto generated stage method parameters so the analyzer can detect tag violations at builder chain call sites.

## Tasks

- [x] 1. Create `@TaggedValue` annotation and model types
  - [x] 1.1 Create `rawit.TaggedValue` meta-annotation
    - Create `src/main/java/rawit/TaggedValue.java`
    - `@Target(ElementType.ANNOTATION_TYPE)`, `@Retention(RetentionPolicy.CLASS)`
    - Declare `boolean strict() default false`
    - Follow the same pattern as `rawit.Invoker`, `rawit.Constructor`, `rawit.Getter`
    - _Requirements: 1.1, 1.2, 1.3_

  - [x] 1.2 Create `rawit.processors.model.TagInfo` record
    - Create `src/main/java/rawit/processors/model/TagInfo.java`
    - Fields: `annotationFqn` (String), `strict` (boolean)
    - Add non-null validation in compact constructor for `annotationFqn`
    - _Requirements: 1.4_

  - [x] 1.3 Create `rawit.processors.model.TagResolution` sealed interface
    - Create `src/main/java/rawit/processors/model/TagResolution.java`
    - Sealed interface with two permitted records: `Tagged(TagInfo tag)` and `Untagged()`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

  - [x] 1.4 Create `rawit.processors.tagged.AssignmentWarning` sealed interface
    - Create `src/main/java/rawit/processors/tagged/AssignmentWarning.java`
    - Sealed interface with three permitted records: `TagMismatch(TagInfo sourceTag, TagInfo targetTag)`, `StrictTaggedToUntagged(TagInfo tag)`, `StrictUntaggedToTagged(TagInfo tag)`
    - Each record implements `String toMessage()` method producing human-readable diagnostic text
    - `TagMismatch.toMessage()` includes both source and target tag annotation names
    - `StrictTaggedToUntagged.toMessage()` and `StrictUntaggedToTagged.toMessage()` include the tag annotation name
    - _Requirements: 12.1, 12.2, 12.3_

- [x] 2. Implement `AssignmentChecker` (core warning decision logic)
  - [x] 2.1 Create `rawit.processors.tagged.AssignmentChecker`
    - Create `src/main/java/rawit/processors/tagged/AssignmentChecker.java`
    - Implement `Optional<AssignmentWarning> check(TagResolution source, TagResolution target, boolean isLiteralOrConst)`
    - Pure function — no dependency on Tree API or `javax.lang.model`
    - Implement the full warning decision matrix from the design:
      - Untagged→Untagged: no warning
      - Tagged(A)→Tagged(A) (same tag): no warning
      - Tagged(A)→Tagged(B) (different tags): `TagMismatch` warning (regardless of strict/lax)
      - Untagged→Tagged(A, strict=true) + literal: no warning (exempt)
      - Untagged→Tagged(A, strict=true) + non-literal: `StrictUntaggedToTagged` warning
      - Untagged→Tagged(A, strict=false): no warning (lax)
      - Tagged(A, strict=true)→Untagged: `StrictTaggedToUntagged` warning
      - Tagged(A, strict=false)→Untagged: no warning (lax)
    - _Requirements: 3.1, 3.2, 4.1, 5.1, 6.1, 7.1, 7.2, 8.1, 9.1_

  - [x] 2.2 Write property test `AssignmentCheckerPropertyTest`
    - Create `src/test/java/rawit/processors/tagged/AssignmentCheckerPropertyTest.java`
    - **Property 3: Tag mismatch always produces a warning regardless of strict/lax mode**
    - **Property 4: Same-tag and untagged-to-untagged assignments never produce warnings**
    - **Property 5: Strict mode warns on tagged-to-untagged and untagged-to-tagged (non-literal)**
    - **Property 6: Lax mode never warns on tagged-to-untagged or untagged-to-tagged**
    - **Property 7: Literals and constants are exempt from strict untagged-to-tagged warnings**
    - **Validates: Requirements 3.1, 3.2, 4.1, 5.1, 6.1, 7.1, 7.2, 8.1, 9.1**

  - [x] 2.3 Write unit test `AssignmentCheckerTest`
    - Create `src/test/java/rawit/processors/tagged/AssignmentCheckerTest.java`
    - Test specific examples from requirements code examples:
      - `@UserId long taggedId = 42` → no warning (literal exempt)
      - `@UserId long taggedId2 = rawId` → `StrictUntaggedToTagged` warning
      - `@FirstName String taggedName = rawName` → no warning (lax mode)
      - `@LastName String lastName = user.firstName()` → `TagMismatch` warning
      - `@FirstName String name2 = name1` → no warning (same tag)
      - `String b = a` → no warning (untagged→untagged)
    - _Requirements: 3.1, 3.2, 4.1, 5.1, 6.1, 7.1, 8.1, 9.1_

- [x] 3. Implement `AssignmentWarning` message formatting tests
  - [x] 3.1 Write property test `AssignmentWarningPropertyTest`
    - Create `src/test/java/rawit/processors/tagged/AssignmentWarningPropertyTest.java`
    - **Property 9: Warning messages contain relevant tag annotation names**
    - Generate random `AssignmentWarning` instances with random tag annotation FQNs
    - Assert: `TagMismatch.toMessage()` contains both source and target annotation names
    - Assert: `StrictTaggedToUntagged.toMessage()` contains the tag annotation name
    - Assert: `StrictUntaggedToTagged.toMessage()` contains the tag annotation name
    - **Validates: Requirements 12.1, 12.2**

- [x] 4. Checkpoint - Verify core logic and models
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Implement `TagDiscoverer`
  - [x] 5.1 Create `rawit.processors.tagged.TagDiscoverer`
    - Create `src/main/java/rawit/processors/tagged/TagDiscoverer.java`
    - Implement `Map<String, TagInfo> discover(RoundEnvironment roundEnv, ProcessingEnvironment processingEnv)`
    - Iterate over elements annotated with `@TaggedValue` in the round
    - For each annotation type element, extract the `strict` attribute value
    - Build and return a map from annotation FQN to `TagInfo`
    - _Requirements: 1.4, 13.2_

  - [x] 5.2 Write property test `TagDiscovererPropertyTest`
    - Create `src/test/java/rawit/processors/tagged/TagDiscovererPropertyTest.java`
    - **Property 1: Tag discovery recognizes exactly @TaggedValue-annotated annotations**
    - Generate random sets of annotation metadata (some with `@TaggedValue`, some without, with random `strict` values)
    - Verify the discoverer returns exactly the correct set with correct `strict` values
    - **Validates: Requirements 1.4**

- [x] 6. Implement `TagResolver`
  - [x] 6.1 Create `rawit.processors.tagged.TagResolver`
    - Create `src/main/java/rawit/processors/tagged/TagResolver.java`
    - Implement `TagResolution resolve(Element element, Map<String, TagInfo> tagMap, Messager messager)`
    - Inspect element's annotations against the known tag map
    - If no recognized tag annotation found: return `Untagged`
    - If one recognized tag annotation found: return `Tagged(tagInfo)`
    - If multiple recognized tag annotations found: return `Tagged(firstTagInfo)` and emit duplicate-tag warning via `Messager`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 11.1, 11.2_

  - [x] 6.2 Write property test `TagResolverPropertyTest`
    - Create `src/test/java/rawit/processors/tagged/TagResolverPropertyTest.java`
    - **Property 2: Tag resolution recognizes tags on all supported element kinds**
    - **Property 8: Multiple tags use first encountered and emit duplicate warning**
    - Generate random elements with random annotation lists against a random tag map
    - Verify correct resolution and duplicate-tag warning emission
    - **Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 11.1, 11.2**

- [x] 7. Checkpoint - Verify discoverer and resolver
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Implement `TaggedValueAnalyzer` (AST scanner)
  - [x] 8.1 Create `rawit.processors.tagged.TaggedValueAnalyzer`
    - Create `src/main/java/rawit/processors/tagged/TaggedValueAnalyzer.java`
    - Implement `void analyze(Map<String, TagInfo> tagMap, CompilationUnitTree compilationUnit, Trees trees, ProcessingEnvironment env)`
    - Use `TreePathScanner` to walk the AST and inspect:
      - Variable declarations with initializers (local variables, fields)
      - Assignment expressions
      - Method invocation arguments (including generated builder chain stage methods)
      - Return statements
    - For each assignment-like expression:
      - Resolve target tag via `TagResolver` (LHS element's annotations)
      - Resolve source tag via `TagResolver` (RHS expression's type/element annotations)
      - Determine if RHS is a literal or compile-time constant (check `LiteralTree`, `IdentifierTree` referencing `static final` with constant value)
      - Delegate to `AssignmentChecker` for warning decision
      - Emit warning via `Messager` if applicable
    - Implement `void analyzeRound(Map<String, TagInfo> tagMap, RoundEnvironment roundEnv, ProcessingEnvironment processingEnv)` convenience method that iterates compilation units
    - Handle graceful degradation: if Tree API is unavailable, silently skip analysis
    - Handle unresolvable elements: silently skip rather than emit spurious warnings
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 3.1, 3.2, 4.1, 5.1, 6.1, 7.1, 7.2, 8.1, 9.1, 10.1, 10.2, 13.3_

- [x] 9. Integrate into `RawitAnnotationProcessor`
  - [x] 9.1 Add `@TaggedValue` processing branch to `RawitAnnotationProcessor`
    - Modify `src/main/java/rawit/processors/RawitAnnotationProcessor.java`
    - Change `getSupportedAnnotationTypes()` to return `Set.of("*")` so the processor is invoked even when tag annotations come from pre-compiled JARs
    - Add early return optimization: if tag map is empty and no rawit annotations are present, return immediately
    - In `process()`, add `@TaggedValue` branch before the early return:
      - Instantiate `TagDiscoverer` and call `discover()` to build the tag map
      - If tag map is non-empty, instantiate `TaggedValueAnalyzer` and call `analyzeRound()`
    - The analyzer is stateless — no initialization needed in `init()`
    - _Requirements: 13.1, 13.2, 13.3_

  - [x] 9.2 Verify META-INF/services registration
    - Confirm `rawit.processors.RawitAnnotationProcessor` is listed in `src/main/resources/META-INF/services/javax.annotation.processing.Processor`
    - No file change needed — the processor class name is unchanged; `getSupportedAnnotationTypes()` update covers `@TaggedValue`
    - _Requirements: 13.1_

- [x] 10. Checkpoint - Verify processor integration compiles
  - Ensure all tests pass, ask the user if questions arise.

- [x] 11. Write integration test
  - [x] 11.1 Write `TaggedValueAnalyzerIntegrationTest`
    - Create `src/test/java/rawit/processors/tagged/TaggedValueAnalyzerIntegrationTest.java`
    - End-to-end: compile test source files with `@TaggedValue` tag annotations using `javax.tools.JavaCompiler` API
    - Test scenarios:
      - Tag mismatch warning emitted for `@FirstName` → `@LastName` assignment
      - Strict untagged→tagged warning emitted for `rawId` → `@UserId` assignment
      - No warning for literal → strict tagged assignment
      - No warning for lax tagged → untagged assignment
      - No warning for same-tag assignment
      - No warning for untagged → untagged assignment
      - Builder chain interaction: tag checking applies to stage method arguments
      - Multiple tags on element: first tag used, duplicate warning emitted
    - Verify all warnings use `Diagnostic.Kind.WARNING` (not ERROR)
    - _Requirements: 3.1, 3.2, 4.1, 5.1, 6.1, 7.1, 7.2, 8.1, 9.1, 10.1, 10.2, 11.1, 11.2, 12.1, 12.2, 12.3, 13.1, 13.2, 13.3_

- [x] 12. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 13. Propagate tag annotations onto generated stage method parameters
  - [x] 13.1 Extend `Parameter` model to carry annotation FQNs
    - Modify `src/main/java/rawit/processors/model/Parameter.java`
    - Add a `List<String> annotationFqns` field to the record (fully qualified names of tag annotations on this parameter)
    - Add a compact constructor that defaults `annotationFqns` to `List.of()` and defensively copies the list
    - Add a convenience constructor `Parameter(String name, String typeDescriptor)` that delegates with an empty annotation list, preserving backward compatibility with all existing call sites
    - _Requirements: 10.3_

  - [x] 13.2 Extend `MergeNode.SharedNode` and `MergeNode.Branch` to carry annotation FQNs
    - Modify `src/main/java/rawit/processors/model/MergeNode.java`
    - Add `List<String> annotationFqns` field to `SharedNode` and `Branch` records
    - Add backward-compatible convenience constructors `SharedNode(String, String, MergeNode)` and `Branch(String, String, MergeNode)` that delegate with an empty annotation list
    - Update `MergeTreeBuilder` to propagate `annotationFqns` from `Parameter` into `SharedNode` and `Branch` when building the tree
    - _Requirements: 10.3_

  - [x] 13.3 Capture tag annotation FQNs in `RawitAnnotationProcessor`
    - Modify `src/main/java/rawit/processors/RawitAnnotationProcessor.java`
    - In `buildAnnotatedMethod()`: for each `VariableElement` parameter, inspect its annotations to find any that are meta-annotated with `@TaggedValue`, collect their FQNs, and pass them to the `Parameter` constructor
    - In `buildAnnotatedMethodFromRecord()`: for each `RecordComponentElement`, inspect its annotations similarly and pass tag annotation FQNs to the `Parameter` constructor
    - Helper method: `List<String> extractTagAnnotationFqns(Element element)` that iterates the element's annotation mirrors, checks if each annotation type is itself annotated with `@TaggedValue`, and collects matching FQNs
    - _Requirements: 10.3_

  - [x] 13.4 Update `StageInterfaceSpec` to emit annotation specs on generated parameters
    - Modify `src/main/java/rawit/processors/codegen/StageInterfaceSpec.java`
    - In `buildStageMethod()`: accept `List<String> annotationFqns` (from the node), and for each FQN, add an `AnnotationSpec` to the generated `ParameterSpec` using `ClassName.bestGuess(fqn)`
    - Update all callers of `buildStageMethod()` to pass the annotation FQNs from the `SharedNode` or `Branch`
    - _Requirements: 10.3_

  - [x] 13.5 Remove "known limitation" Javadoc from `TaggedValueAnalyzer`
    - Modify `src/main/java/rawit/processors/tagged/TaggedValueAnalyzer.java`
    - Remove the `@Known limitation` paragraph from the class Javadoc since tag annotations are now propagated onto generated stage method parameters
    - _Requirements: 10.3_

- [x] 14. Write tests for tag annotation propagation
  - [x] 14.1 Write property test `StageInterfaceAnnotationPropagationPropertyTest`
    - Create `src/test/java/rawit/processors/codegen/StageInterfaceAnnotationPropagationPropertyTest.java`
    - **Property 10: Generated stage method parameters carry propagated tag annotations**
    - Generate random `AnnotatedMethod` models with parameters carrying random tag annotation FQN lists
    - Build a `MergeTree` from the method, run `StageInterfaceSpec.buildAll()`
    - For each generated stage interface, verify that the stage method's `ParameterSpec` carries `AnnotationSpec` entries matching the original parameter's tag annotation FQNs
    - Parameters with empty annotation lists should produce parameters with no annotation specs
    - Minimum 100 iterations
    - **Validates: Requirements 10.3**

  - [x] 14.2 Write unit test for annotation propagation in `StageInterfaceSpecTest`
    - Add test cases to existing `src/test/java/rawit/processors/codegen/StageInterfaceSpecTest.java` (or create a new focused test class)
    - Test specific examples:
      - A `@Constructor` record with `@UserId long userId, @FirstName String firstName, @LastName String lastName` produces stage methods where `userId` param has `@UserId`, `firstName` param has `@FirstName`, `lastName` param has `@LastName`
      - A parameter with no tag annotations produces a generated parameter with no annotation specs
      - A parameter with multiple tag annotations propagates all of them
    - _Requirements: 10.3_

  - [x] 14.3 Add integration test for end-to-end annotation propagation
    - Add test case(s) to existing `src/test/java/rawit/processors/tagged/TaggedValueAnalyzerIntegrationTest.java`
    - Compile a `@Constructor` record with tagged parameters, then compile client code that passes mismatched tagged values to the generated builder chain
    - Verify that the `TaggedValueAnalyzer` emits the expected tag mismatch warning at the builder call site (e.g., passing a `@FirstName` value to `.lastName(...)`)
    - This validates the full pipeline: capture → carry → emit → analyze
    - _Requirements: 10.1, 10.2, 10.3_

- [x] 15. Checkpoint - Verify annotation propagation
  - Ensure all new and existing tests pass
  - Verify that the `TaggedValueAnalyzer` known limitation is resolved

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific examples and edge cases
- The `AssignmentChecker` is the most testable component — pure function with no external dependencies
- The `TaggedValueAnalyzer` uses the javac Tree API (`TreePathScanner`) which requires running under javac; graceful degradation for non-javac compilers
- `@TaggedValue` uses `RetentionPolicy.CLASS` (unlike `SOURCE` for other rawit annotations) so tag metadata survives into `.class` files for downstream projects
- No bytecode modification or source generation is involved — this feature only emits warnings
- Tasks 13–15 implement tag annotation propagation (Requirement 10.3): extending the `Parameter` model, propagating through `MergeNode`, emitting annotations in generated code, and removing the analyzer's known limitation about generated builder chains
