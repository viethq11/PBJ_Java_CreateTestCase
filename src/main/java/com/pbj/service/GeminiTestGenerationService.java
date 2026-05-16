package com.pbj.service;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.pbj.dto.AiResponseDTO;
import com.pbj.dto.AiProblemAnalysisDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GeminiTestGenerationService {
    private static final Pattern COMMAND_TOKEN_PATTERN = Pattern.compile("\\b(update|query)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCALAR_HINT_PATTERN = Pattern.compile("\\b([A-Z][A-Z0-9]{0,7})\\b");

    @Value("${ai.gemini.api-key:}")
    private String geminiApiKey;

    @Value("${ai.gemini.api-keys:}")
    private String geminiApiKeys;

    @Value("${ai.gemini.pro-model:gemini-2.5-flash}")
    private String geminiProModel;

    @Value("${ai.gemini.timeout-seconds:120}")
    private int timeoutSeconds;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private volatile long lastSuccessfulPreflightAtMs;
    private static final long GEMINI_PREFLIGHT_TTL_MS = 5 * 60 * 1000L;

    public String extractProblemText(String hint, List<String> base64Images) {
        String prompt = """
                You are an expert OCR system for competitive programming problems.
                Read the attached image(s) carefully and extract the COMPLETE problem statement as plain text.
                Preserve all mathematical formulas, comparisons, exponents, and constraints exactly.
                Prefer plain-text math that survives web rendering: <=, >=, !=, 10^5, 2^31-1, a_i, x mod m.
                Do not turn inequalities such as a < b into HTML tags or omit the comparison operators.
                Preserve all examples and line breaks.
                Output ONLY the extracted problem text. No explanations, no JSON, just the raw problem text.
                """
                + (hint != null && !hint.isBlank() ? "\nAdditional context from user: " + hint : "");

        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", prompt));
        if (base64Images != null) {
            for (String b64 : base64Images) {
                parts.add(Map.of("inline_data", Map.of("mime_type", "image/jpeg", "data", b64)));
            }
        }

        return executeGeminiRequest(Map.of("contents", List.of(Map.of("parts", parts))), "Gemini (OCR Extract)");
    }

    public void verifyApiKeysBeforePipeline() {
        long now = System.currentTimeMillis();
        if (now - lastSuccessfulPreflightAtMs < GEMINI_PREFLIGHT_TTL_MS) {
            return;
        }

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", "Reply with OK.")))),
                "generationConfig", Map.of(
                        "temperature", 0,
                        "maxOutputTokens", 16
                )
        );
        executeGeminiRequest(requestBody, "Gemini API key preflight");
        lastSuccessfulPreflightAtMs = System.currentTimeMillis();
    }

    public AiProblemAnalysisDTO analyzeProblem(String problemText) {
        String prompt = buildAnalysisPrompt(problemText);
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "temperature", 0.1
                )
        );
        String responseText = executeGeminiRequest(requestBody, "Gemini (Problem Analysis)");
        try {
            return objectMapper.readValue(stripMarkdownFences(responseText), AiProblemAnalysisDTO.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Gemini problem analysis response: " + e.getMessage(), e);
        }
    }

    public AiResponseDTO generateTestArtifacts(String problemText, AiProblemAnalysisDTO analysis, int count) {
        String prompt = buildGenerationPromptV2(problemText, analysis, count);
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "temperature", 0.2
                )
        );
        String responseText = executeGeminiRequest(requestBody, "Gemini (Test Generation V2)");
        return parseAnalysisResponse(responseText);
    }

    public AiResponseDTO generateReferenceCandidates(String problemText, AiResponseDTO frozenDto) {
        String prompt = buildReferenceCandidatePrompt(problemText, frozenDto);
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "temperature", 0.1
                )
        );
        String responseText = executeGeminiRequest(requestBody, "Gemini (Reference Candidate Recovery)");
        return parseAnalysisResponse(responseText);
    }

    public AiResponseDTO repairFormalSpec(String problemText, AiResponseDTO brokenDto, String validationError) {
        try {
            String brokenJson = objectMapper.writeValueAsString(brokenDto);
            String sourceAnchors = buildSourceAnchors(problemText);
            String forbiddenCommands = buildForbiddenCommandGuidance(problemText);

            String prompt = """
                    You are repairing a competitive-programming formal specification JSON.

                    Original problem statement is authoritative.
                    The previous JSON failed backend validation.

                    Validation error:
                    %s

                    Repair rules:
                    - Return ONLY valid JSON with the same schema as the previous response.
                    - Do NOT use "unknown", "unspecified", or "not specified" anywhere.
                    - Do NOT put "unknown" in input_schema min/max/length/rows/cols.
                    - Every numeric min/max must be either:
                      1) a number,
                      2) an earlier scalar such as N or M,
                      3) scalar-minus-constant such as N-1.
                    - If a bound is missing, infer a safe default:
                      count variables: 1..100000
                      generic int values: -1000000000..1000000000
                      positive int values: 1..1000000000
                      node indices: 1..N
                      binary flags/types: 0..1
                    - Put uncertainty into validator_rules or assumptions, not into input_schema.
                    - The original problem statement is the single source of truth. Delete any field, command vocabulary,
                      or repeated-row structure that is not explicitly supported by the original statement.
                    - Keep the exact input order and tuple width from the original statement.
                    - golden_solution must be a complete compilable C++ reference solution for the original problem.

                    Source-grounding anchors:
                    %s

                    Forbidden drift to remove if present:
                    %s

                    Original problem:
                    %s

                    Broken JSON:
                    %s
                    """.formatted(
                    validationError == null ? "" : validationError,
                    sourceAnchors,
                    forbiddenCommands,
                    problemText == null ? "" : problemText,
                    brokenJson);

            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(Map.of("text", prompt))
                    )),
                    "generationConfig", Map.of(
                            "responseMimeType", "application/json",
                            "temperature", 0.1
                    )
            );

            String responseText = executeGeminiRequest(requestBody, "Gemini (Formal Spec Repair)");
            return parseAnalysisResponse(responseText);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to repair formal spec: " + e.getMessage(), e);
        }
    }

    private String buildGenerationPrompt(String problemText, String analysisJson, int count) {
        String sourceAnchors = buildSourceAnchors(problemText);
        String forbiddenCommands = buildForbiddenCommandGuidance(problemText);
        return """
                You are an expert Competitive Programming problem setter and testcase engineer.
                You will receive the original problem and a short local analysis_json produced by Ollama.
                Use BOTH. The original problem is authoritative; analysis_json is only a hint.
                If analysis_json describes a different task, ignore it completely.
                First reconstruct the formal problem specification internally. Then output metadata only.
                If a required constraint is not explicitly present in the original problem:
                - Do NOT write "unknown" in input_schema.min, input_schema.max, length, rows, cols, columns[].min, or columns[].max.
                - For input_schema numeric bounds, infer the safest competitive-programming default from context.
                - If the variable is a count such as N, M, Q, T: use min = 1 unless the statement clearly allows 0.
                - If the variable is a node index: use min = 1 and max = N.
                - If the variable is a generic integer value and no bound is shown: use min = -1000000000 and max = 1000000000.
                - If the variable is a positive integer value and no bound is shown: use min = 1 and max = 1000000000.
                - If the variable is a binary/type flag: use min = 0 and max = 1.
                - Put any uncertainty in validator_rules or assumptions, NOT in input_schema.
                - The constraints field must not contain the words unknown, unspecified, or not specified.
                Do not introduce concepts, input sections, variables, or constraints that are absent from the original problem.
                Never introduce command-style operations such as UPDATE/QUERY unless the original problem explicitly uses them.
                Examples of forbidden drift: inventing DAG/A[i]/s,t for a graph-edge problem that only has u,v,W,type.
                Source-grounding anchors extracted from the original statement:
                %s
                Forbidden drift to avoid:
                %s
                Your job is normalized specification extraction plus executable testcase artifacts.

                Original problem:
                %s

                analysis_json:
                %s

                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                ABSOLUTE RULE #1 — NEVER GENERATE HUGE RAW TESTCASES
                NEVER output raw testcase inputs, raw testcase outputs, large arrays, N=100000 raw lines,
                or manually computed expected outputs.

                ABSOLUTE RULE #2 — TESTCASE ARTIFACTS ONLY
                Return ONLY normalized formal specification and testcase-generation artifacts.
                Do NOT generate standalone user-facing AC submissions.
                The only executable artifacts you may generate are:
                - generator_code
                - golden_solution
                - validator_code
                - bruteforce_solution
                - wrong_solutions[].code
                golden_solution must be a complete compilable C++17 reference program matching input_schema exactly.
                Do not output raw edge_cases or manually compute/invent any expected outputs.
                The bruteforce_solution must be a complete compilable program for tiny/small cases only,
                matching input_schema exactly and producing authoritative output for small/edge verification.
                wrong_solutions[].code must be plausible incorrect or slow programs that the generated tests should kill.
                Do not simulate games, exponent towers, or processes step-by-step when constraints require a formula,
                modular arithmetic, dynamic programming, or logarithmic/linear-time reasoning, unless the artifact is explicitly bruteforce_solution.

                ABSOLUTE RULE #3 — BUG-ORIENTED VALIDATION GATES
                Do not design tests around a vague quality score. Design them so the backend validation gates can pass:
                generator pass, validator pass, trusted-reference pass, WA probe separation, TLE/complexity probe separation,
                mandatory golden-vs-bruteforce agreement on every small/edge testcase, and required profile coverage.
                Include named test-family functions that target likely wrong solutions from analysis_json.
                Include complexity probes that kill brute-force/TLE approaches under max constraints.
                Use numeric extremes near min/max constraints, e.g. 10^9, -10^9, and 64-bit sums.
                You are not only generating edge cases. You are designing tests to kill common wrong submissions.
                Explicitly analyze integer overflow, wrong greedy choices, and boundary/off-by-one risks.

                ABSOLUTE RULE #4 — JSON SYNTAX & ESCAPING
                Output ONLY a valid JSON object. No markdown.
                Inside string values, represent newlines using literal \\n.
                Inside string values, escape all double-quotes as \\" and all backslashes as \\\\.
                - Do not include *_b64 fields.
                - golden_solution must be raw compilable C++17 source code only.
                - Every code field must be raw source code only, with no markdown fences or explanations.

                ABSOLUTE RULE #5 — VIETNAMESE USER-FACING STATEMENT
                The user-facing problem statement fields MUST be written in Vietnamese:
                - formatted_description: Vietnamese only, preserving formulas, variable names, and math notation.
                - input_format: Vietnamese only, clearly explaining every input line/token.
                - output_format: Vietnamese only, clearly explaining what to print.
                - constraints: Vietnamese only, formatted as a compact readable list.
                Keep machine-readable fields such as input_schema, code, identifiers, and JSON keys unchanged.

                ABSOLUTE RULE #6 — MARKDOWN + LATEX STATEMENT FORMAT
                The web UI renders these fields as sanitized Markdown with MathJax:
                formatted_description, input_format, output_format, constraints.
                Therefore:
                - All problem statement fields must be written in Markdown.
                - Use LaTeX math notation for formulas and constraints:
                  inline math: $...$
                  block math: $$...$$
                - Use \\le instead of <= and \\ge instead of >= inside math.
                - Write indexed values as math such as $a_i$, not plain a_i.
                - Write powers inside math such as $10^5$.
                - Write ranges as math such as $1 \\le N \\le 10^6$.
                - Do NOT write raw forms such as 1 <= N <= 10^6, a_i, or 10^9 in user-facing fields.
                - Do NOT output HTML tags, code fences, or raw <sub>/<sup>.
                - Use short paragraphs and bullet lines beginning with "- " for lists.
                - Use Markdown lists where lists are useful.
                - Do NOT insert hard line breaks inside a normal paragraph just to wrap text visually.
                  Keep each paragraph on one line in the JSON string, and use blank lines only between paragraphs.
                - Preserve comparison direction exactly. Never drop symbols such as <, >, <=, >=.
                - If the original contains a strict inequality like 1 < n <= 10^5, write it as math while preserving the same meaning.
                - Do not write angle-bracket placeholders such as <N> or <value>; write `N`, `value`, or plain variable names.
                - Do not wrap variable names or formulas in backticks unless they are literal code tokens.
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

                Return EXACTLY this JSON structure:
                {
                  "formatted_description": "Mô tả bài toán bằng tiếng Việt, chia thành các đoạn ngắn bằng \\n\\n. Công thức dùng Markdown + LaTeX như $a_i$, $10^5$, $x \\le y$.",
                  "understanding": "Brief summary of the problem logic",
                  "input_format": "Dòng 1: ...\\nDòng 2: ...\\nCác dòng tiếp theo: ...",
                  "output_format": "In ra ... trên một dòng. Nếu có nhiều kết quả, mỗi kết quả trên một dòng.",
                  "constraints": "- $1 \\le N \\le 10^5$\\n- $0 \\le a_i \\le 10^9$\\n- Tổng kích thước dữ liệu không vượt quá ...",
                  "input_schema": {
                    "multiple_test_cases": false,
                    "lines": [
                      {
                        "kind": "scalars",
                        "fields": [
                          {"name": "N", "type": "int", "min": 1, "max": 200000},
                          {"name": "M", "type": "int", "min": 1, "max": 200000}
                        ]
                      },
                      {"kind": "array", "name": "A", "type": "int", "length": "N", "min": 1, "max": 1000000000},
                      {
                        "kind": "edges",
                        "length": "M",
                        "directed": true,
                        "self_loop_allowed": false,
                        "multi_edge_allowed": false,
                        "columns": [
                          {"name": "u", "type": "node", "min": 1, "max": "N"},
                          {"name": "v", "type": "node", "min": 1, "max": "N"},
                          {"name": "w", "type": "int", "min": 1, "max": 1000000000}
                        ]
                      }
                    ]
                  },
                  "checker_code": "Java Checker code if special judge needed, else empty string",
                  "validator_code": "",
                  "bug_classes": [
                    {
                      "name": "INTEGER_OVERFLOW",
                      "risk": "sum/product/path cost can exceed int32 or int64",
                      "target_variables": ["sum", "answer", "dist"],
                      "required_tests": ["overflow_int32", "overflow_int64_if_relevant"],
                      "counterexample_strategy": ["max_n_max_value", "long_path_accumulation"]
                    }
                  ],
                  "test_plan": {
                    "problem_type": "one enum value: GRAPH_ALTERNATING_EDGE_SHORTEST_PATH|GRAPH_SHORTEST_PATH|DAG_DP|TREE_DP|GRID_BFS|ARRAY_PREFIX_SUM|ARRAY_TWO_POINTERS|STRING_MATCHING|MATH_NUMBER_THEORY|GENERIC_SCHEMA|UNKNOWN",
                    "intended_solution": "Correct algorithm and complexity",
                    "wrong_solutions": [
                      {
                        "name": "wrong_approach_name",
                        "why_wrong": "Why it fails",
                        "counterexample_strategy": "How to generate cases that expose it"
                      }
                    ],
                    "test_families": [
                      {
                        "name": "family_name_used_by_generator",
                        "difficulty": "small|medium|large|stress",
                        "target": ["wrong_approach_name"],
                        "constraints": "Input shape and size limits for this family",
                        "expected": "Expected behavior category, not raw output",
                        "reason": "Why this family is valuable"
                      }
                    ],
                    "generator_requirements": {
                      "must_include_bruteforce_for_small": true,
                      "must_include_large_stress": true,
                      "must_include_complexity_traps": true,
                      "must_include_numeric_extremes": true,
                      "must_avoid_raw_large_data": true
                    }
                  },
                  "wrong_solutions": [
                    {
                      "name": "overflow_probe",
                      "type": "overflow",
                      "idea": "Uses int for accumulated answer",
                      "language": "cpp",
                      "expected_to_fail": true,
                      "killed_by_profiles": ["overflow_int32"],
                      "code": ""
                    },
                    {
                      "name": "greedy_probe",
                      "type": "greedy",
                      "idea": "Always takes the locally best move",
                      "language": "cpp",
                      "expected_to_fail": true,
                      "killed_by_profiles": ["anti_greedy_small", "tie_breaking"],
                      "code": ""
                    }
                  ],
                  "test_profiles": [
                    {
                      "name": "BOUNDARY_MIN",
                      "objective": "touch minimum and maximum boundaries safely",
                      "difficulty": "small",
                      "seed_count": 2,
                      "targets_wrong_solutions": ["boundary_probe"],
                      "required": true
                    },
                    {
                      "name": "OVERFLOW_INT32",
                      "objective": "force accumulated values above 2^31-1 whenever relevant",
                      "difficulty": "large",
                      "seed_count": 2,
                      "targets_wrong_solutions": ["overflow_probe"],
                      "required": true
                    },
                    {
                      "name": "ADVERSARIAL_GREEDY",
                      "objective": "small counterexample that defeats natural greedy choices",
                      "difficulty": "small",
                      "seed_count": 2,
                      "targets_wrong_solutions": ["greedy_probe"],
                      "required": true
                    }
                  ],
                  "total_testcases": %d,
                  "generator_language": "cpp",
                  "generator_code": "Complete generator source code",
                  "golden_solution": "Complete C++17 reference solution source code",
                  "bruteforce_solution": "Complete brute force source code for tiny/small cases",
                  "bruteforce_language": "cpp",
                  "validator_rules": ["rule 1", "rule 2"],
                  "generation_strategy": {
                    "small_cases": true,
                    "random_cases": true,
                    "edge_cases": true,
                    "stress_cases": true
                  },
                  "edge_cases": []
                }

                test_plan requirements:
                - Provide enough normalized detail for a separate local C++ generator model.
                - test_plan.problem_type must be one of the listed taxonomy enum values, not free prose.
                - Ground every field in the original problem. If the original statement says every edge has u, v, W, type,
                  then input_schema.edges.columns must contain exactly u, v, W, type in that order.
                - For graph n,m up to 2e5, specify valid indexing, directedness, self-loop/multi-edge policy, edge weights/types, and max sparse traps.
                - For array/window/sum problems, specify numeric min/max, n max, k-sensitive cases, and values near +/-1e9 when allowed.
                - Include at least one overflow-related or greedy-related wrong solution whenever such risks plausibly exist.
                - If a natural greedy looks suspicious, provide the smallest counterexample strategy you can.
                - If the answer can exceed 2^31-1, include overflow_int32 in bug_classes/test_profiles.
                - If the answer can exceed 2^63-1, mention that explicitly in bug_classes and generator/test profile design.

                input_schema requirements:
                - It must describe the exact input tokens in order, line by line.
                - Use only machine-readable JSON, not prose.
                - Supported line kinds: scalars, array, matrix, edges, queries, grid, string, raw_lines.
                - Do NOT use wrapper kinds such as loop, repeat, for, foreach, group, block, or section.
                  Repeated rows must be represented directly as:
                  edges for graph edges, queries for repeated tuple/query rows, array for one repeated value list,
                  matrix/grid for 2D repeated values, or raw_lines only when the row shape is genuinely not structured.
                - For each scalar/array/matrix value include numeric min/max when known.
                - Use length references such as "N", "M", "Q", "N-1" only when that scalar appears earlier.
                - For graph-like data include node indexing, directedness, self-loop policy, multi-edge policy, and every column.
                - If the statement is complex but specified, provide the safest broad schema that always generates valid input.
                - If the statement is missing required constraint facts, infer the safest competitive-programming defaults above
                  and record uncertainty in validator_rules or assumptions instead of input_schema.
                
                edge_cases:
                - Always return an empty array. The backend owns all testcase inputs and expected outputs.
                checker_code: empty string for unique-output problems.
                wrong_solutions requirements:
                - Provide at least 3 plausible wrong-solution metadata entries when feasible: overflow, greedy, boundary/off-by-one.
                - code should contain a compilable incorrect or slow probe whenever feasible; the backend will execute these probes directly.
                - Keep wrong solutions concise but complete.
                - Use exact type names from this set whenever relevant: overflow, greedy, boundary, off_by_one, brute_force, tle.
                - If bug_classes includes overflow, include at least one wrong_solution metadata entry with type="overflow".
                - If bug_classes includes greedy, include at least one wrong_solution metadata entry with type="greedy".
                - If a slow brute-force approach is plausible, include one wrong_solution metadata entry with type="tle" or "brute_force".
                - For each wrong_solution, set killed_by_profiles to the most likely testcase profiles that should defeat it.
                executable artifact requirements:
                - generator_code may be empty only if you genuinely cannot derive a safe generator from the statement.
                - validator_code may be empty only if input_schema is sufficient for the backend to rebuild it exactly.
                - golden_solution is mandatory and must solve the full constraints correctly.
                - bruteforce_solution is mandatory whenever small/exhaustive or boundary verification is feasible.
                - Never output raw testcase inputs or expected outputs.
                test_profiles requirements:
                - Use these structured profile names when possible:
                  SAMPLE, SMALL_EXHAUSTIVE, BOUNDARY_MIN, BOUNDARY_MAX,
                  RANDOM_SMALL, RANDOM_MEDIUM, RANDOM_LARGE,
                  OVERFLOW_INT32, OVERFLOW_INT64,
                  DUPLICATE_VALUES, TIE_BREAKING,
                  ADVERSARIAL_GREEDY, ADVERSARIAL_SORTING, ADVERSARIAL_GRAPH_STRUCTURE,
                  STRESS_PERFORMANCE.
                - Required baseline coverage must be balanced: include small, boundary, medium, random large, adversarial, and stress profiles.
                - Do not make most profiles near maximum constraints. Only RANDOM_LARGE and STRESS_PERFORMANCE should be near max.
                - SMALL_EXHAUSTIVE, BOUNDARY_MIN/MAX, ADVERSARIAL_GREEDY, and TIE_BREAKING should stay tiny enough for brute-force reasoning.
                - Add overflow profiles only when numeric overflow is relevant.
                Final language check before returning:
                - formatted_description, input_format, output_format, and constraints must not be English prose.
                - If the original statement is English, translate those four fields to Vietnamese.
                - Do not translate variable names, constants, sample input/output data, or source code.
                - Ensure every comparison, exponent, and index in user-facing fields is valid Markdown + LaTeX:
                  $x \\le y$, $10^5$, $2^{31}-1$, $a_i$, $O(n \\log n)$.
                - Never output raw HTML or code fences in user-facing fields.
                """.formatted(
                sourceAnchors,
                forbiddenCommands,
                problemText == null ? "" : problemText,
                analysisJson == null ? "{}" : analysisJson,
                count);
    }

    private String buildReferenceCandidatePrompt(String problemText, AiResponseDTO frozenDto) {
        String frozenSpec;
        try {
            Map<String, Object> frozenFields = new java.util.LinkedHashMap<>();
            frozenFields.put("input_format", frozenDto == null || frozenDto.getInputFormat() == null ? "" : frozenDto.getInputFormat());
            frozenFields.put("output_format", frozenDto == null || frozenDto.getOutputFormat() == null ? "" : frozenDto.getOutputFormat());
            frozenFields.put("constraints", frozenDto == null || frozenDto.getConstraints() == null ? "" : frozenDto.getConstraints());
            frozenFields.put("input_schema", frozenDto == null ? null : frozenDto.getInputSchema());
            frozenFields.put("test_plan", frozenDto == null ? null : frozenDto.getTestPlan());
            frozenSpec = objectMapper.writeValueAsString(frozenFields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize frozen reference spec: " + e.getMessage(), e);
        }

        return """
                You are generating candidate reference programs for a competitive-programming problem.

                The original problem statement is authoritative.
                A normalized backend-validated specification is already frozen below.
                Do NOT reinterpret the task, do NOT change the schema, and do NOT invent missing concepts.

                Original problem:
                %s

                Frozen normalized specification:
                %s

                Return ONLY valid JSON with these fields:
                {
                  "golden_solution": "Complete compilable C++17 optimized reference solution",
                  "bruteforce_solution": "Complete compilable C++17 tiny-case oracle",
                  "bruteforce_language": "cpp"
                }

                Rules:
                - golden_solution is only a candidate; the backend will verify it independently.
                - bruteforce_solution must be intentionally simple and authoritative for tiny/small valid inputs.
                - Derive both programs from the original statement plus the frozen specification.
                - Respect input_schema exactly.
                - Output raw source code only inside each code field; no markdown fences or explanations.
                - If the optimized solution and brute-force method use the same algorithmic idea, still implement the
                  brute-force program separately in the simplest direct way feasible for tiny inputs.
                """.formatted(problemText == null ? "" : problemText, frozenSpec);
    }

    private String buildSourceAnchors(String problemText) {
        if (problemText == null || problemText.isBlank()) {
            return "- No source anchors available.";
        }

        String normalized = problemText.replace('\r', '\n');
        String[] lines = normalized.split("\\n+");
        LinkedHashSet<String> anchors = new LinkedHashSet<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.contains("input")
                    || lower.contains("output")
                    || lower.contains("constraint")
                    || lower.contains("dòng")
                    || lower.contains("dong")
                    || lower.contains("mỗi")
                    || lower.contains("moi")
                    || lower.contains("gồm")
                    || lower.contains("gom")
                    || lower.contains("contains")
                    || lower.contains("followed by")) {
                anchors.add("- " + trimmed);
            }
            Matcher scalarMatcher = SCALAR_HINT_PATTERN.matcher(trimmed);
            while (scalarMatcher.find() && anchors.size() < 8) {
                anchors.add("- Identifier in source: " + scalarMatcher.group(1));
            }
            if (anchors.size() >= 8) break;
        }
        if (anchors.isEmpty()) {
            anchors.add("- Problem excerpt: " + normalized.substring(0, Math.min(normalized.length(), 240)).trim());
        }
        return String.join("\n", anchors);
    }

    private String buildForbiddenCommandGuidance(String problemText) {
        if (problemText == null || problemText.isBlank()) {
            return "- Do not invent command tokens or operation names.";
        }

        String lower = problemText.toLowerCase(Locale.ROOT);
        List<String> forbidden = new ArrayList<>();
        Matcher matcher = COMMAND_TOKEN_PATTERN.matcher("update query");
        while (matcher.find()) {
            String command = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!lower.matches("(?s).*\\b" + command + "\\b.*")) {
                forbidden.add("- Remove command token '" + command + "' unless quoted from the source statement.");
            }
        }
        if (forbidden.isEmpty()) {
            forbidden.add("- No extra command-style operations beyond the source statement.");
        }
        return String.join("\n", forbidden);
    }

    private AiResponseDTO parseAnalysisResponse(String responseText) {
        if (responseText == null || responseText.isBlank()) return new AiResponseDTO();

        String cleanedText = stripMarkdownFences(responseText)
                .replace("{\\n", "{")
                .replace("[\\n", "[")
                .replace(",\\n", ",");

        try {
            JsonNode root = readJsonWithContext(cleanedText);
            AiResponseDTO dto = new AiResponseDTO();
            dto.setUnderstanding(root.path("understanding").asText(""));
            dto.setFormattedDescription(normalizeStatementMarkdown(root.path("formatted_description").asText("")));
            dto.setInputFormat(normalizeStatementMarkdown(root.path("input_format").asText(root.path("input").asText(""))));
            dto.setOutputFormat(normalizeStatementMarkdown(root.path("output_format").asText(root.path("output").asText(""))));
            dto.setConstraints(normalizeStatementMarkdown(root.path("constraints").asText("")));
            JsonNode inputSchemaNode = root.path("input_schema");
            if (!inputSchemaNode.isMissingNode() && !inputSchemaNode.isNull()) {
                dto.setInputSchema(normalizeInputSchema(inputSchemaNode));
            }
            dto.setCheckerCode(root.path("checker_code").asText(""));
            dto.setValidatorCode(readCodeField(root, "validator_code"));
            dto.setTotalTestcases(root.path("total_testcases").asInt(10));
            dto.setGeneratorLanguage(root.path("generator_language").asText("python"));
            
            // New Base64 support
            if (root.has("generator_code_b64")) {
                dto.setGeneratorCodeB64(root.path("generator_code_b64").asText(""));
                dto.setGeneratorCode(decodeBase64(dto.getGeneratorCodeB64()));
            } else {
                dto.setGeneratorCode(root.path("generator_code").asText(""));
            }

            if (root.has("golden_solution_b64")) {
                dto.setGoldenSolutionB64(root.path("golden_solution_b64").asText(""));
                dto.setGoldenSolution(decodeBase64(dto.getGoldenSolutionB64()));
            } else {
                dto.setGoldenSolution(readCodeField(root, "golden_solution"));
            }

            if (root.has("bruteforce_solution_b64")) {
                dto.setBruteForceSolutionB64(root.path("bruteforce_solution_b64").asText(""));
                dto.setBruteForceSolution(decodeBase64(dto.getBruteForceSolutionB64()));
            } else {
                dto.setBruteForceSolution(readCodeField(root, "bruteforce_solution"));
            }

            if (root.has("validator_code_b64")) {
                dto.setValidatorCodeB64(root.path("validator_code_b64").asText(""));
                dto.setValidatorCode(decodeBase64(dto.getValidatorCodeB64()));
            } else {
                dto.setValidatorCode(readCodeField(root, "validator_code"));
            }

            if (root.has("input_model")) {
                dto.setInputModel(root.path("input_model"));
            }

            dto.setBruteForceLanguage(root.path("bruteforce_language").asText("cpp"));

            JsonNode testPlanNode = root.path("test_plan");
            if (!testPlanNode.isMissingNode() && !testPlanNode.isNull()) {
                dto.setTestPlan(objectMapper.treeToValue(testPlanNode, AiResponseDTO.TestPlan.class));
            }

            JsonNode bugClassesNode = root.path("bug_classes");
            if (bugClassesNode.isArray()) {
                dto.setBugClasses(objectMapper.readerForListOf(AiResponseDTO.BugClass.class).readValue(bugClassesNode));
            }

            JsonNode wrongSolutionsNode = root.path("wrong_solutions");
            if (wrongSolutionsNode.isArray()) {
                List<AiResponseDTO.ExecutableWrongSolution> wrongSolutions = new ArrayList<>();
                for (JsonNode node : wrongSolutionsNode) {
                    AiResponseDTO.ExecutableWrongSolution ws = new AiResponseDTO.ExecutableWrongSolution();
                    ws.setName(node.path("name").asText(""));
                    ws.setType(node.path("type").asText(""));
                    ws.setIdea(node.path("idea").asText(""));
                    ws.setLanguage(node.path("language").asText(""));
                    ws.setExpectedToFail(node.path("expected_to_fail").asBoolean(true));
                    if (node.path("killed_by_profiles").isArray()) {
                        List<String> killed = new ArrayList<>();
                        node.path("killed_by_profiles").forEach(k -> killed.add(k.asText("")));
                        ws.setKilledByProfiles(killed);
                    }
                    ws.setCode(readCodeField(node, "code"));
                    wrongSolutions.add(ws);
                }
                dto.setWrongSolutions(wrongSolutions);
            }

            JsonNode testProfilesNode = root.path("test_profiles");
            if (testProfilesNode.isArray()) {
                dto.setTestProfiles(objectMapper.readerForListOf(AiResponseDTO.TestProfile.class).readValue(testProfilesNode));
            }

            JsonNode validatorNode = root.path("validator_rules");
            if (validatorNode.isArray()) {
                List<String> rules = new ArrayList<>();
                validatorNode.forEach(n -> rules.add(n.asText()));
                dto.setValidatorRules(rules);
            }

            JsonNode stratNode = root.path("generation_strategy");
            if (!stratNode.isMissingNode()) {
                AiResponseDTO.GenerationStrategy strat = new AiResponseDTO.GenerationStrategy();
                strat.setSmallCases(stratNode.path("small_cases").asBoolean(true));
                strat.setRandomCases(stratNode.path("random_cases").asBoolean(true));
                strat.setEdgeCases(stratNode.path("edge_cases").asBoolean(true));
                strat.setStressCases(stratNode.path("stress_cases").asBoolean(true));
                dto.setGenerationStrategy(strat);
            }

            JsonNode edgeCasesNode = root.path("edge_cases");
            if (edgeCasesNode.isArray()) {
                List<AiResponseDTO.TestCaseDTO> edgeCases = new ArrayList<>();
                for (JsonNode ec : edgeCasesNode) {
                    AiResponseDTO.TestCaseDTO tc = new AiResponseDTO.TestCaseDTO();
                    tc.setInput(ec.path("input").asText(""));
                    tc.setExpectedOutput(ec.path("expected_output").asText(""));
                    tc.setIsSample(ec.path("is_sample").asBoolean(false));
                    edgeCases.add(tc);
                }
                dto.setEdgeCases(edgeCases);
            }
            return dto;
        } catch (Exception e) {
            System.err.println("\n=== RAW GEMINI RESPONSE (FOR DEBUGGING) ===");
            System.err.println(cleanedText);
            System.err.println("==========================================\n");
            throw new RuntimeException("Failed to parse Gemini test generation response: " + e.getMessage(), e);
        }
    }

    private JsonNode readJsonWithContext(String jsonText) throws JsonProcessingException {
        try {
            return objectMapper.readTree(jsonText);
        } catch (JsonProcessingException e) {
            long offset = e.getLocation() == null ? -1L : e.getLocation().getCharOffset();
            String context = jsonContext(jsonText, offset);
            throw new JsonProcessingException(e.getOriginalMessage()
                    + (context.isBlank() ? "" : " Near: " + context), e) {};
        }
    }

    private String jsonContext(String text, long offset) {
        if (text == null || text.isBlank() || offset < 0L) return "";
        int center = (int) Math.min(offset, text.length());
        int start = Math.max(0, center - 180);
        int end = Math.min(text.length(), center + 180);
        return text.substring(start, end)
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    public JsonNode normalizeInputSchema(JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) return schema;
        ObjectNode normalized = schema.deepCopy();
        JsonNode lines = normalized.path("lines");
        if (!lines.isArray()) return normalized;

        ArrayNode normalizedLines = objectMapper.createArrayNode();
        for (JsonNode line : lines) {
            appendNormalizedLine(normalizedLines, line);
        }
        normalized.set("lines", normalizedLines);
        return normalized;
    }

    private void appendNormalizedLine(ArrayNode target, JsonNode line) {
        String kind = line.path("kind").asText("").trim().toLowerCase();
        if (kind.equals("tuple") || kind.equals("tuples") || kind.equals("record") || kind.equals("records")) {
            target.add(normalizeTupleLine(line));
            return;
        }
        if (!kind.equals("loop") && !kind.equals("repeat") && !kind.equals("for_each")) {
            target.add(line.deepCopy());
            return;
        }

        JsonNode length = firstPresent(line, "length", "count", "times", "repeat");
        JsonNode body = firstPresent(line, "body", "lines", "items", "line");
        if (body.isArray() && body.size() == 1 && "scalars".equalsIgnoreCase(body.get(0).path("kind").asText(""))) {
            target.add(tupleLikeLine("queries", length, body.get(0)));
            return;
        }
        if (body.isObject() && "scalars".equalsIgnoreCase(body.path("kind").asText(""))) {
            target.add(tupleLikeLine("queries", length, body));
            return;
        }

        if (body.isArray()) {
            for (JsonNode child : body) {
                target.add(withInheritedLength(child, length));
            }
            return;
        }
        if (body.isObject()) {
            target.add(withInheritedLength(body, length));
            return;
        }

        ObjectNode rawLines = objectMapper.createObjectNode();
        rawLines.put("kind", "raw_lines");
        if (!length.isMissingNode() && !length.isNull()) {
            rawLines.set("length", length.deepCopy());
        }
        target.add(rawLines);
    }

    private JsonNode normalizeTupleLine(JsonNode line) {
        ObjectNode normalized = line.deepCopy();
        JsonNode fields = firstPresent(line, "columns", "fields", "items");
        if (fields.isArray()) {
            normalized.set("columns", fields.deepCopy());
            normalized.remove("fields");
            normalized.remove("items");
        }

        JsonNode length = firstPresent(line, "length", "count", "times", "repeat");
        if (!length.isMissingNode() && !length.isNull()) {
            normalized.put("kind", "queries");
            normalized.set("length", length.deepCopy());
            return normalized;
        }

        normalized.put("kind", "scalars");
        if (normalized.path("fields").isMissingNode() && normalized.path("columns").isArray()) {
            normalized.set("fields", normalized.path("columns").deepCopy());
        }
        normalized.remove("columns");
        return normalized;
    }

    private ObjectNode tupleLikeLine(String kind, JsonNode length, JsonNode scalarLine) {
        ObjectNode tuple = objectMapper.createObjectNode();
        tuple.put("kind", kind);
        if (!length.isMissingNode() && !length.isNull()) {
            tuple.set("length", length.deepCopy());
        }
        JsonNode fields = scalarLine.path("fields");
        if (fields.isArray()) {
            tuple.set("columns", fields.deepCopy());
        }
        return tuple;
    }

    private JsonNode withInheritedLength(JsonNode line, JsonNode length) {
        ObjectNode copy = line.deepCopy();
        String kind = copy.path("kind").asText("").trim().toLowerCase();
        if (Set.of("array", "string", "edges", "queries", "raw_lines").contains(kind)
                && copy.path("length").isMissingNode()
                && !length.isMissingNode()
                && !length.isNull()) {
            copy.set("length", length.deepCopy());
        }
        return copy;
    }

    private JsonNode firstPresent(JsonNode node, String... fields) {
        if (node == null) return objectMapper.missingNode();
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) return value;
        }
        return objectMapper.missingNode();
    }

    private String readCodeField(JsonNode root, String plainFieldName) {
        String encodedFieldName = plainFieldName + "_b64";
        String encoded = root.path(encodedFieldName).asText("").trim();
        if (!encoded.isBlank()) {
            return decodeBase64Utf8(encoded, encodedFieldName);
        }
        return root.path(plainFieldName).asText("");
    }

    private String decodeBase64Utf8(String value, String fieldName) {
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid base64 in field " + fieldName + ": " + e.getMessage(), e);
        }
    }

    private String executeGeminiRequest(Map<String, Object> requestBody, String errorPrefix) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        long[] retryDelaysMs = {3000L, 5000L, 10000L, 20000L};
        int maxRetries = retryDelaysMs.length + 1;
        RuntimeException lastFailure = null;

        for (String apiKey : resolveGeminiApiKeys()) {
            String keyLabel = maskKey(apiKey);

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);
                    ResponseEntity<String> response = buildRestTemplate().postForEntity(geminiUrl(apiKey), entity, String.class);
                    JsonNode rootNode = objectMapper.readTree(response.getBody());
                    JsonNode textNode = rootNode.path("candidates").path(0)
                            .path("content").path("parts").path(0)
                            .path("text");
                    if (textNode.isMissingNode() || textNode.asText("").isBlank()) {
                        throw new IllegalStateException("Gemini returned no text candidate. Response="
                                + truncate(rootNode.toString(), 500));
                    }
                    return textNode.asText();
                } catch (HttpServerErrorException.ServiceUnavailable | HttpClientErrorException.TooManyRequests e) {
                    String responseBody = truncate(e.getResponseBodyAsString(), 500);
                    System.err.println("WARN: " + errorPrefix + " key " + keyLabel
                            + " attempt " + attempt + "/" + maxRetries
                            + " failed with HTTP " + e.getStatusCode() + ": " + responseBody);

                    if (isHardQuotaExceeded(responseBody)) {
                        lastFailure = new RuntimeException(errorPrefix + " key " + keyLabel
                                + " đã hết quota Gemini. HTTP " + e.getStatusCode()
                                + (responseBody.isBlank() ? "" : " - " + responseBody), e);
                        System.err.println("WARN: " + errorPrefix + " key " + keyLabel
                                + " quota exhausted; trying next Gemini key if available.");
                        break;
                    }

                    lastFailure = new RuntimeException(errorPrefix + " API đang quá tải hoặc vượt quota. HTTP "
                            + e.getStatusCode() + (responseBody.isBlank() ? "" : " - " + responseBody), e);

                    if (attempt == maxRetries) {
                        System.err.println("WARN: " + errorPrefix + " key " + keyLabel
                                + " exhausted retry attempts; trying next Gemini key if available.");
                        break;
                    }

                    long sleepMs = retryDelaysMs[attempt - 1];
                    System.err.println("WARN: " + errorPrefix + " key " + keyLabel
                            + " retrying after " + sleepMs + " ms.");
                    try { Thread.sleep(sleepMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                } catch (Exception e) {
                    lastFailure = new RuntimeException(errorPrefix + " Failed with key " + keyLabel + ": " + e.getMessage(), e);
                    System.err.println("WARN: " + errorPrefix + " key " + keyLabel
                            + " failed before quota classification: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                    break;
                }
            }
        }

        throw new RuntimeException(buildGeminiFailureMessage(errorPrefix, lastFailure), lastFailure);
    }

    private String geminiUrl(String apiKey) {
        return "https://generativelanguage.googleapis.com/v1beta/models/"
                + geminiProModel + ":generateContent?key=" + apiKey;
    }

    private String buildGeminiFailureMessage(String errorPrefix, RuntimeException lastFailure) {
        String detail = lastFailure == null || lastFailure.getMessage() == null
                ? ""
                : lastFailure.getMessage();
        String lower = detail.toLowerCase(Locale.ROOT);

        if (lower.contains("quota") || lower.contains("resource_exhausted") || lower.contains("429")) {
            return errorPrefix + " thất bại vì quota/rate limit của Gemini. "
                    + "Hãy kiểm tra đúng project/model đang dùng hoặc thêm key khác vào GEMINI_API_KEYS.";
        }
        if (lower.contains("api key not valid")
                || lower.contains("permission_denied")
                || lower.contains("unauthenticated")
                || lower.contains("403")
                || lower.contains("401")) {
            return errorPrefix + " thất bại vì API key Gemini không hợp lệ hoặc không có quyền dùng model đã chọn.";
        }
        return errorPrefix + " thất bại trước khi xác nhận quota Gemini. "
                + "Nguyên nhân gần nhất: " + truncate(detail, 240);
    }

    private List<String> resolveGeminiApiKeys() {
        List<String> keys = new ArrayList<>();

        addConfiguredKeys(keys, geminiApiKeys);
        addConfiguredKeys(keys, geminiApiKey);

        if (keys.isEmpty()) {
            throw new IllegalStateException("Chưa cấu hình Gemini API key. Hãy đặt GEMINI_API_KEY hoặc GEMINI_API_KEYS.");
        }

        return keys;
    }

    private void addConfiguredKeys(List<String> keys, String configuredValue) {
        if (configuredValue == null || configuredValue.isBlank()) return;

        for (String key : configuredValue.split("[,;\\r\\n]+")) {
            String trimmed = key.trim();
            if (!trimmed.isBlank() && !keys.contains(trimmed)) {
                keys.add(trimmed);
            }
        }
    }

    private String maskKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) return "****";
        return apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
    }

    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutSeconds * 1000);
        factory.setReadTimeout(timeoutSeconds * 1000);
        return new RestTemplate(factory);
    }

    private String stripMarkdownFences(String text) {
        if (text == null) return "";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?s)```(?:\\w+)?\\s*\\n?(.*?)```");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return text.trim();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) return normalized;
        return normalized.substring(0, maxLength) + "...";
    }

    String normalizeStatementMarkdown(String text) {
        if (text == null || text.isBlank()) return text == null ? "" : text;
        if (text.contains("$")) return text;

        return normalizePlainStatementSegment(text);
    }

    private String normalizePlainStatementSegment(String text) {
        StringBuilder normalizedText = new StringBuilder();
        String[] lines = text.split("(?<=\\n)", -1);
        for (String line : lines) {
            normalizedText.append(normalizePlainStatementLine(line));
        }
        return normalizedText.toString();
    }

    private String normalizePlainStatementLine(String line) {
        String normalized = line;
        normalized = normalized.replaceAll(
                "(?m)(?<!\\$)(\\d+)\\s*<=\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*<=\\s*(10\\^\\d+|\\d+)(?!\\$)",
                "\\$$1 \\\\le $2 \\\\le $3\\$");
        normalized = normalized.replaceAll(
                "(?m)(?<!\\$)(\\d+)\\s*>=\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*>=\\s*(10\\^\\d+|\\d+)(?!\\$)",
                "\\$$1 \\\\ge $2 \\\\ge $3\\$");
        normalized = normalized.replaceAll(
                "(?m)(?<!\\$)(\\d+)\\s*<=\\s*([A-Za-z_][A-Za-z0-9_]*)(?!\\s*<=)(?!\\$)",
                "\\$$1 \\\\le $2\\$");
        normalized = normalized.replaceAll(
                "(?m)(?<!\\$)([A-Za-z_][A-Za-z0-9_]*)\\s*<=\\s*(10\\^\\d+|\\d+)(?!\\$)",
                "\\$$1 \\\\le $2\\$");
        normalized = normalized.replaceAll(
                "(?m)(?<!\\$)(\\d+)\\s*>=\\s*([A-Za-z_][A-Za-z0-9_]*)(?!\\s*>=)(?!\\$)",
                "\\$$1 \\\\ge $2\\$");
        normalized = normalized.replaceAll(
                "(?m)(?<!\\$)([A-Za-z_][A-Za-z0-9_]*)\\s*>=\\s*(10\\^\\d+|\\d+)(?!\\$)",
                "\\$$1 \\\\ge $2\\$");
        if (!normalized.contains("$")) {
            normalized = normalized.replaceAll("\\b([A-Za-z]+_[A-Za-z0-9]+)\\b", "\\$$1\\$");
            normalized = normalized.replaceAll("\\b(10\\^\\d+)\\b", "\\$$1\\$");
        }
        return normalized;
    }

    private boolean isHardQuotaExceeded(String responseBody) {
        if (responseBody == null) return false;
        String lower = responseBody.toLowerCase();
        return lower.contains("quota exceeded")
                && (lower.contains("limit: 0") || lower.contains("exceeded your current quota"));
    }

    private String decodeBase64(String b64) {
        if (b64 == null || b64.isBlank()) return "";
        try {
            return new String(Base64.getDecoder().decode(b64.trim()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("WARN: Failed to decode Base64: " + e.getMessage());
            return "";
        }
    }

    private String buildAnalysisPrompt(String problemText) {
        return """
                You are a competitive programming expert. Analyze the following problem statement.
                Goal: Identify the problem type, algorithm family, and structure the input model.
                
                Input Model Patterns to support:
                - single_case: simple one-time input
                - multi_test: T test cases, each with same structure
                - array_based: focus on one or more arrays
                - graph_edges: N nodes, M edges (u, v, w)
                - tree_edges: N nodes, N-1 edges
                - grid: H x W grid of values
                - command_based: sequence of different operations (UPDATE, QUERY, etc.)
                - multiple_sections: distinct parts in input
                
                Analyze risks: integer overflow, time limits (TLE), precision, corner cases.
                
                Problem statement:
                %s
                
                Return ONLY valid JSON:
                {
                  "problem_type": "specific_problem_type_name",
                  "algorithm_family": "intended_algorithm_or_data_structure",
                  "input_pattern": "one_of_the_patterns_above",
                  "constraints": "summary of N, M, values",
                  "template_id": "optional_suggested_template_id",
                  "input_model": {
                    "type": "multi_test_command_based_or_other",
                    "test_count": "T",
                    "blocks": [
                      {
                        "header": ["n", "m"],
                        "repeat": "m",
                        "variants": [
                          { "keyword": "UPDATE", "args": ["x", "y", "z"] },
                          { "keyword": "QUERY", "args": ["x1", "y1", "x2", "y2"] }
                        ]
                      }
                    ]
                  },
                  "risk_tags": ["overflow", "tle_risk", "precision", "complex_input"],
                  "analysis_summary": "detailed internal logic"
                }
                """.formatted(problemText);
    }

    private String buildGenerationPromptV2(String problemText, AiProblemAnalysisDTO analysis, int count) {
        String analysisJson;
        try {
            analysisJson = objectMapper.writeValueAsString(analysis);
        } catch (JsonProcessingException e) {
            analysisJson = "{}";
        }

        return """
                You are a professional competitive programming testcase engineer.
                Based on the problem statement and the preliminary analysis, generate the necessary artifacts.
                
                Problem Statement:
                %s
                
                Preliminary Analysis:
                %s
                
                ABSOLUTE RULE #1: CODE FIELDS MUST BE BASE64
                To avoid JSON parsing errors with special characters or keywords like UPDATE/QUERY,
                all source code fields MUST be returned as Base64-encoded strings.
                
                Fields to return in Base64:
                - generator_code_b64
                - bruteforce_solution_b64
                - golden_solution_b64
                - validator_code_b64
                - wrong_solutions[].code_b64
                
                ABSOLUTE RULE #2: TWO SOLUTIONS
                - golden_solution: Optimized, intended for large constraints.
                - bruteforce_solution: Correct but simple/slow, intended for small/tiny constraints.
                
                ABSOLUTE RULE #3: INPUT MODEL
                Use the provided input_model from the analysis. Ensure the generator respects it perfectly.
                
                ABSOLUTE RULE #4: VIETNAMESE USER-FACING STATEMENT
                The formatted_description, input_format, output_format, constraints must be in Vietnamese with LaTeX math ($...$).
                
                Return EXACTLY this JSON structure:
                {
                  "formatted_description": "...",
                  "input_format": "...",
                  "output_format": "...",
                  "constraints": "...",
                  "input_model": { ... },
                  "generator_code_b64": "Base64 encoded C++ generator",
                  "golden_solution_b64": "Base64 encoded C++17 optimized solution",
                  "bruteforce_solution_b64": "Base64 encoded C++ simple solution",
                  "validator_code_b64": "Base64 encoded C++ input validator",
                  "test_profiles": [ ... ],
                  "bug_classes": [ ... ],
                  "wrong_solutions": [
                    {
                       "name": "...",
                       "type": "...",
                       "code_b64": "Base64 encoded code"
                    }
                  ],
                  "total_testcases": %d
                }
                """.formatted(problemText, analysisJson, count);
    }
}
