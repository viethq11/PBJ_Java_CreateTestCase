# PBJ Judge Contract-First Rebuild

## Goal

Rebuild testcase generation so AI is no longer the final authority for executable artifacts. AI may propose a problem contract, but backend code must validate the contract, generate inputs from it, and reject any artifact that disagrees with it before judge data is saved.

## Pipeline

1. **Ingest statement**
   - Accept text and/or image.
   - OCR only extracts statement text.
   - No generator, validator, testcase, or solution code is accepted here.

2. **Build contract**
   - AI proposes a structured `ProblemContract`.
   - The contract describes input sections, commands, bounds, output semantics, and risk areas.
   - The contract is schema-validated and semantically validated by backend code.

3. **Generate backend-owned inputs**
   - Backend generates testcase input from the contract.
   - `multipleTestCases=true` always emits `T`.
   - Command sections emit only declared keywords and argument counts.
   - Bounds are resolved from previously generated variables.

4. **Preflight**
   - Every generated input must parse against the same contract.
   - Failures stop before golden/reference execution.
   - This catches bugs like Power Tower generators omitting `T`.

5. **Oracle gate**
   - Small cases must pass brute-force/reference agreement.
   - Large cases can be accepted only after the trusted reference passes preflighted inputs.
   - AI-generated reference code is provisional until it passes backend checks.

6. **Persist judge data**
   - Save only backend-owned, contract-validated, oracle-checked testcase files.
   - UI may preview truncated data, but judge always reads full files.

## Migration Plan

1. Add the v2 contract model, validator, and deterministic input generator.
2. Cover known failure classes with tests, starting with command-based multi-test inputs.
3. Add an adapter from existing AI DTOs to `ProblemContract`.
4. Run v1 and v2 side by side behind a feature flag.
5. Switch `/api/problem/generate` to v2 when the contract gate can cover scalar, array, grid, tree, graph, and command-based inputs.

## Non-Goals For The First Slice

- No UI redesign.
- No complete replacement of existing `ProblemService`.
- No per-problem hardcoded fallback as the primary strategy.
