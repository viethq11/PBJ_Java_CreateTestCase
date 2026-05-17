# PBJ Judge Rebuild Status And Roadmap

## Why The System Changed

The old generation flow allowed Gemini to produce too many executable artifacts directly: generator code, validator code, golden solution, and sometimes inferred input schema. This made the system fragile. A single mismatch between those artifacts could break testcase generation even when OCR and semantic understanding were mostly correct.

The Power Tower failure exposed the core problem:

- The statement clearly required a leading `T` test count.
- Gemini generated testcase input without `T`.
- The generated golden solution expected `T`.
- The validator/golden pipeline then rejected every generated case.

This showed that patching individual problems is not a sustainable refactor. The system needs backend-owned contracts and backend-owned testcase generation.

## What Changed

### 1. Contract-First Core Added

New package:

```text
src/main/java/com/pbj/v2/contract
```

Key classes:

- `ProblemContract`
- `InputSection`
- `ScalarField`
- `CommandVariant`
- `Bound`
- `ContractValidator`
- `ContractTestcaseGenerator`
- `ContractPreflightService`
- `KnownContracts`

This core models the input contract explicitly and validates generated input before any oracle or judge step runs.

### 2. Family Classifier Replaces Name-Based Detection

New package:

```text
src/main/java/com/pbj/v2/generation
```

The v2 generation entrypoint now classifies a statement by input/output structure and semantic signals before selecting a generator. The classifier recognizes these families:

- `SCALAR_ONLY`
- `ARRAY`
- `COMMAND_BASED`
- `GRID`
- `GRAPH_TREE`
- `RANGE_QUERY_UPDATE`
- `NUMERIC_OVERFLOW_STRESS`
- `DYNAMIC_PROGRAMMING`
- `NUMBER_THEORY`
- `DATA_STRUCTURE`
- `STRING`
- `GENERAL`
- `COMBINATORICS`
- `CONSTRUCTIVE`
- `DIGIT_PRODUCT_FACTORIZATION`
- `GAME_THEORY`

This means the system no longer needs to ask "is this exact title Power Tower?" or "is this exact title Bouquet?". It asks "what shape and semantics does this statement describe?".

### 2b. Pattern Inference Added Inside Families

Family recognition is now only the first gate. `ProblemPatternAnalyzer` infers a narrower trusted pattern from semantic evidence before a backend oracle is selected.

Examples:

- `GAME_THEORY` + rightmost-cow/empty-stall/Hieu-RR evidence -> `MAX_WELTER_COW_GAME`
- `GAME_THEORY` + remove `{1,2,3}` stones evidence -> `SUBTRACTION_GAME`
- generic turn-taking text without a trusted move model -> `UNKNOWN`, which fails early instead of silently using the wrong oracle

This moves v2 away from title-specific special cases and toward explicit, auditable reasoning:

```text
statement -> family -> trusted pattern -> contract + generator + oracle
```

### 3. Backend-Owned Generation For Supported Families

New service:

```text
src/main/java/com/pbj/v2/generation/V2ProblemGenerationService.java
```

For command/grid problems such as Power Tower-style statements, the system now:

- Detects the family from text/OCR structure.
- Creates problem metadata directly.
- Generates testcase inputs in Java.
- Always emits the required leading `T`.
- Computes expected output with a backend oracle.
- Stores testcase files through the existing storage layer.
- Stores a C++ accepted solution for later judge verification.

For digit/product/factorization problems such as Bouquet-style statements, the system now:

- Detects the family from digit/product/factorization semantics.
- Generates scalar `n k` testcase inputs in Java.
- Computes expected output with a backend oracle.
- Stores sample-like and edge testcase files.
- Stores a C++ accepted solution for judge verification.

Gemini no longer generates testcase generator code or golden code for supported v2 families.

### 4. Main Generate Path Redirected

`ProblemService.generateAndSaveProblemFromBase64(...)` now delegates to `V2ProblemGenerationService`.

The old Gemini-heavy implementation is still present as a legacy path so the project compiles while replacement continues, but it is no longer the primary generation path.

### 5. OCR Is No Longer Fatal

If an image is uploaded, v2 may still use Gemini OCR to extract statement text. If OCR fails because of quota, parsing, or API failure, v2 falls back to the user-provided text instead of failing the entire generation flow.

### 6. Testcase Preview Was Optimized

Problem pages no longer render full large testcase files into HTML. The UI now renders preview text, while the judge still reads full testcase files from disk.

## Current Verification

The following checks passed:

```text
mvn clean package -DskipTests
mvn -Dtest=com.pbj.v2.contract.ContractPreflightServiceTest,com.pbj.v2.generation.ProblemStructureClassifierTest test
docker-compose up -d --build
```

Runtime verification:

- Bouquet-style digit/product/factorization generation through `/api/problem/generate` returned `DONE`.
- The generated Bouquet problem had 8 testcase files.
- Submitting a known accepted C++ solution returned `AC`, `8/8`.
- Power Tower-style command/grid generation through `/api/problem/generate` returned `DONE` even when the title was changed to `Any 3D Command Problem`, confirming detection is structural rather than title-based.

Recognition verification has also been exercised against harder real contest statements from ICPC 2023 Southern, including:

- `Goals`
- `Exact Permutation Mapping`
- `Intricate Polygons`
- `Journey to Sequence Sum`
- `Mingle Lineup`

These statements are useful because they describe the same underlying techniques through story-heavy wording rather than exposing keywords such as `Fenwick`, `bitmask`, or `tree DP`.

## Current Limitations

The v2 path currently has trusted backend generators for:

- `SCALAR_ONLY`
- `ARRAY`
- `COMMAND_BASED` / `GRID`
- `GRAPH_TREE`
- `RANGE_QUERY_UPDATE`
- `NUMERIC_OVERFLOW_STRESS`
- `DYNAMIC_PROGRAMMING`
- `NUMBER_THEORY`
- `DATA_STRUCTURE`
- `STRING`
- `GENERAL`
- `COMBINATORICS`
- `CONSTRUCTIVE`
- `DIGIT_PRODUCT_FACTORIZATION`
- `GAME_THEORY`

The classifier can recognize the other requested families, but unsupported families intentionally fail early with a clear message until they have backend-owned generators and oracle gates.

Unsupported or incomplete areas:

- Generalization inside each supported family is still incomplete, but v2 now distinguishes family from trusted pattern instead of assuming one exemplar per family.
- Current trusted seed patterns are gcd-pair, maximum-subarray, array-sum-overflow, tree-distance-query, tree-subtree aggregation, graph reachability, weighted graph shortest path, DSU connected-components, DAG topological order, 3D command-grid, range-sum queries, Fenwick point-update/range-query, segment-tree range-update/range-query, matrix danger-detection pathing, coin-change DP, 0/1 knapsack, LIS, bitmask assignment, Fibonacci-power modular sum, weighted sequence sum, substring equality, lower-bound search, minimum-feasible search, two-pointers pair counting, greedy interval scheduling, insertion/inversion minimization, polygon subset counting, constructive permutation, digit-product-factorization, Max-Welter cow-game, and subtraction-game shapes.
- Some patterns are recognized but not yet safe to generate because they still lack backend-owned oracle strategy:
  - `EXPECTED_VALUE_OPTIMAL_PLAY`
  - `PERMUTATION_RANK_UNRANK`
  - `PRIME_COUNT`
  - `NCR_MOD`
  - `STRING_KMP_COUNT`
- The seed corpus is still uneven. Easy textbook statements are overrepresented relative to story-heavy contest statements, which can make classification look stronger than it really is.

### Seed Coverage Matrix

| Group | Seed patterns currently present |
| --- | --- |
| Graph | BFS/DFS reachability, Dijkstra shortest path, DSU connected components, topological order, tree DP subtree aggregation |
| DP | Coin change, 0/1 knapsack, LIS, bitmask assignment |
| Data Structure | Prefix/range sum, Fenwick point-update/range-query, segment-tree range-update/range-query |
| Math | Modular Fibonacci sum, weighted sequence sum, digit/product factorization |
| String | substring equality / hashing seed |
| General | Prefix sum, lower-bound binary search, minimum-feasible search, two pointers, greedy interval scheduling, insertion/inversion minimization |
| Combinatorics | polygon subset counting seed |
| Constructive | even-then-odd permutation |
- Hard-statement recognition seeds from ICPC 2023 Southern are tracked for expected-value games and permutation rank/unrank, while polygon subset counting, weighted sequence sums, and insertion/inversion minimization now also have backend-owned seed generators and brute-force oracles.
- Native string sections are not modeled yet; the current hashing seed uses integer-coded characters.
- Interactive/adaptive problems.
- Output-only or special checker problems.
- Automatic AI-to-contract conversion for arbitrary statements.
- Full oracle strategy selection for every problem class.

The old services still exist because several tests and legacy workflows reference them. They should be removed only after v2 covers the same responsibilities.

## Direction From Here

### Phase 0: Build A Real Evaluation Corpus

Before adding many more one-off patterns, the system needs a benchmark that can reveal whether it is improving in practice.

- Curate a statement corpus from real contests, not only hand-written seeds.
- Include easy, medium, and story-heavy variants for every family.
- Track three scores independently:
  - family classification accuracy
  - pattern recognition accuracy
  - backend-generation readiness
- Keep a regression set of known failure cases such as `Danger Detection`, `Harry's Magical Number`, `Fibonacci Power`, and `Goals`.
- Add a report command that prints:
  - correctly classified
  - recognized but unsupported
  - unknown
  - false-positive pattern selections

### Phase 1: Finish The Contract Language

The current contract model is good enough for many seeds, but not yet expressive enough for harder contest inputs.

- Add native support for:
  - strings and character alphabets
  - matrices and rectangular grids
  - trees and graphs as first-class sections
  - `EOF`-driven records
  - line variants such as the mixed record format in permutation rank/unrank problems
  - dependent dimensions such as `2n`
  - global sum constraints across test cases
  - optional sections selected by an earlier mode field
- Add structural validation for:
  - undeclared references
  - inconsistent command arity
  - impossible bounds
  - invalid repeated sections
- Add round-trip tests:
  - contract -> generated input -> validator -> parser
  - malformed input -> validator rejection

### Phase 2: Separate Recognition From Generation Explicitly

The UI and service layer should model three different states instead of collapsing them together:

1. `family recognized`
2. `pattern recognized`
3. `trusted backend generator available`

This prevents misleading failures and makes the roadmap visible in product behavior.

- Return structured generation diagnostics rather than only one exception string.
- Show recognized family, recognized pattern, evidence, and support state in the UI.
- Persist unsupported-but-recognized examples so they can become future training and regression fixtures.
- Prefer `recognized but unsupported` over `UNKNOWN` whenever the semantic evidence is strong.

### Phase 3: Add Harder Backend Patterns

Prioritize patterns that unlock realistic contest coverage, not only more taxonomy labels.

Next high-value implementations:

1. `PERMUTATION_RANK_UNRANK`
   - requires line variants / EOF-record contract support
   - brute-force oracle for small `n`, optimized Catalan-style logic later
2. `STRING_KMP_COUNT`
   - requires native strings in `ProblemContract`
   - easy brute-force oracle, useful for string coverage
3. `PRIME_COUNT`
   - deterministic oracle and good number-theory seed
4. `NCR_MOD`
   - basic combinatorics seed with modular arithmetic
5. `EXPECTED_VALUE_OPTIMAL_PLAY`
   - keep recognition now
   - add generation only after deriving and testing a trusted mathematical oracle

The generator library should continue using small brute-force domains first, then expand profiles only after the optimized oracle agrees with brute force on randomized cross-checks.

### Phase 4: Introduce An Oracle Registry

Oracle quality is now the main limiter, more than family detection.

- Register each pattern with:
  - brute-force oracle
  - optimized oracle, if any
  - metamorphic checks
  - supported size envelope
  - checker type
- Enforce a trust ladder:
  1. statement samples
  2. brute-force agreement on small inputs
  3. optimized-oracle agreement on randomized fuzz cases
  4. metamorphic/property checks for large profiles
- Refuse persistence when no oracle path is available for the chosen profile.
- Add oracle differential tests so a later refactor cannot silently corrupt outputs.

### Phase 5: Add Coverage-Guided Test Generation

Seed cases should become the starting point, not the final product.

- Move v2 from `seed-first` generation toward `profile-first` generation:
  - current v2 patterns often emit only `2-4` handcrafted seed cases
  - the legacy pipeline could attempt up to `20` generation runs across multiple profiles
  - v2 should regain that breadth without returning to untrusted AI-generated artifacts
- Introduce a backend-owned profile layer such as:
  - `boundary`
  - `small_random`
  - `duplicate_heavy`
  - `monotone`
  - `anti_monotone`
  - `tie_case`
  - `overflow`
  - `adversarial`
  - `medium`
  - `stress`
- Target a normal generation envelope of roughly `12-20` cases per supported pattern, with the exact count depending on:
  - oracle cost
  - contract size limits
  - checker type
  - whether large profiles are safe for that pattern
- Generate testcase profiles for:
  - boundary values
  - duplicates and equalities
  - monotone vs anti-monotone arrays
  - disconnected and degenerate graph shapes
  - overflow pressure
  - adversarial tie cases
  - random small exhaustive cross-checks
  - larger stress cases within oracle limits
- Track which semantic branches each profile targets.
- Prefer generators that can mutate a seed into nearby hard variants instead of only emitting fixed examples.
- Keep seed fixtures for readability and regression tests, but do not let them remain the whole testcase suite for mature patterns.

### Phase 6: Add AI Only As A Drafting Layer

Instead of asking AI for executable artifacts, ask it for constrained intermediate drafts.

Planned adapter:

```text
AI statement/OCR -> contract draft + semantic hints -> backend validation -> backend generation
```

AI output must be rejected if:

- `multipleTestCases=true` but no `T` bounds exist
- a repeated section references an undeclared variable
- line variants or commands have ambiguous arity
- constraints reference unknown variables
- the proposed pattern has no compatible oracle

The AI may suggest likely patterns and candidate evidence, but the backend remains responsible for validation and generation.

### Phase 7: Replace The Legacy Pipeline

Only remove old behavior after v2 has measurable coverage over the evaluation corpus.

- Keep the legacy path available while it is still needed by tests or unsupported flows.
- Add a coverage gate before removing it:
  - recognition accuracy target
  - generation readiness target
  - zero-regression requirement on the benchmark corpus
- Then remove:
  - legacy Gemini artifact generation
  - generator repair/retry loops
  - AI-cache cleanup tied to broken executable artifacts
- Keep Gemini only for OCR and constrained contract drafting.

## Immediate Next Milestones

1. Add the evaluation corpus and machine-readable support report.
2. Extend the contract model with native strings, line variants, EOF records, and dependent dimensions.
3. Add a v2 profile-generation layer so mature patterns emit roughly `12-20` cases instead of only a few handcrafted seeds.
4. Implement `PERMUTATION_RANK_UNRANK` and `STRING_KMP_COUNT`.
5. Add an oracle registry with support envelopes and differential tests.
6. Revisit `EXPECTED_VALUE_OPTIMAL_PLAY` only after its oracle is derived and verified independently.

## Design Principle

AI may propose. Backend must verify. Only backend-owned, contract-validated, oracle-checked testcase data should enter the judge.
