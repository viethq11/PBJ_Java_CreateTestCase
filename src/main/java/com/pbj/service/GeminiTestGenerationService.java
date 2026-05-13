package com.pbj.service;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.pbj.dto.AiResponseDTO;
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
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

@Service
public class GeminiTestGenerationService {

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

    public String extractProblemText(String hint, List<String> base64Images) {
        String prompt = """
                You are an expert OCR system for competitive programming problems.
                Read the attached image(s) carefully and extract the COMPLETE problem statement as plain text.
                Preserve all mathematical formulas using LaTeX notation ($...$ or $$...$$).
                Preserve all constraints, examples, and formatting.
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

    public AiResponseDTO generateTestArtifacts(String problemText, String analysisJson, int count) {
        String prompt = buildGenerationPrompt(problemText, analysisJson, count);
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "temperature", 0.2
                )
        );
        String responseText = executeGeminiRequest(requestBody, "Gemini (Test Generation)");
        return parseAnalysisResponse(responseText);
    }

    public String generateCode(String title, String description, String inputFormat, String outputFormat,
                               String constraints, String language, String type) {
        String promptType = switch (type) {
            case "AC" -> "You are a Competitive Programming Grandmaster. Write a perfect Accepted solution for the problem below. Use the optimal algorithm, handle all edge cases (overflow, N=max, etc.). Return ONLY the source code, no explanation, no markdown fences.";
            case "WA" -> "Write a subtly Wrong Answer solution for the problem below. It should pass sample tests but fail on edge cases (e.g., integer overflow, wrong logic for boundary, missed corner case). Return ONLY the source code.";
            case "TLE" -> "Write a Time Limit Exceeded solution for the problem below. Use correct logic but with excessive time complexity (e.g., O(N²) when N=10^5, or unoptimized recursion without memoization). Return ONLY the source code.";
            default -> "Solve the following problem. Return ONLY the source code.";
        };

        String prompt = """
                %s
                Language: %s

                Title: %s

                Description:
                %s

                Input:
                %s

                Output:
                %s

                Constraints:
                %s
                """.formatted(promptType, language, title, description, inputFormat, outputFormat, constraints);

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))
        );
        return stripMarkdownFences(executeGeminiRequest(requestBody, "Gemini (Code:" + type + ")"));
    }

    private String buildGenerationPrompt(String problemText, String analysisJson, int count) {
        return """
                You are an expert Competitive Programming problem setter and testcase engineer.
                You will receive the original problem and a short local analysis_json produced by Ollama.
                Use BOTH. The original problem is authoritative; analysis_json is only a hint.
                If analysis_json describes a different task, ignore it completely.
                First reconstruct the formal problem specification internally. Only then write artifacts from that spec.
                If a required input/output/constraint/guarantee is not present in the original problem, write "unknown"
                in the relevant field instead of guessing. The backend will request a repair instead of using guessed artifacts.
                Do not introduce concepts, input sections, variables, or constraints that are absent from the original problem.
                Examples of forbidden drift: inventing DAG/A[i]/s,t for a graph-edge problem that only has u,v,W,type.

                Original problem:
                %s

                analysis_json:
                %s

                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                ABSOLUTE RULE #1 — NEVER GENERATE HUGE RAW TESTCASES
                NEVER output large arrays, N=100000 raw lines, or manually computed expected outputs.

                ABSOLUTE RULE #2 — SPEC FIRST, LIMITED PROBE CODE ONLY
                Return ONLY normalized formal specification and test planning metadata.
                Do NOT include generator, validator, golden-solution, or brute-force source code in this phase.
                generator_code, golden_solution, validator_code, bruteforce_solution must be empty strings.
                The only source code allowed is concise executable wrong_solutions[].code probe code.

                ABSOLUTE RULE #3 — BUG-ORIENTED VALIDATION GATES
                Do not design tests around a vague quality score. Design them so the backend validation gates can pass:
                generator pass, validator pass, golden-solution pass, WA probe separation, TLE/complexity probe separation,
                and required profile coverage.
                Include named test-family functions that target likely wrong solutions from analysis_json.
                Include complexity probes that kill brute-force/TLE approaches under max constraints.
                Use numeric extremes near min/max constraints, e.g. 10^9, -10^9, and 64-bit sums.
                You are not only generating edge cases. You are designing tests to kill common wrong submissions.
                Explicitly analyze integer overflow, wrong greedy choices, and boundary/off-by-one risks.

                ABSOLUTE RULE #4 — JSON SYNTAX & ESCAPING
                Output ONLY a valid JSON object. No markdown.
                Inside string values, represent newlines using literal \\n.
                Inside string values, escape all double-quotes as \\" and all backslashes as \\\\.
                Because code is forbidden in this phase:
                - Keep generator_code, golden_solution, validator_code, and bruteforce_solution empty.
                - wrong_solutions[].code may contain short valid C++17 code that intentionally fails the target bug.

                ABSOLUTE RULE #5 — VIETNAMESE USER-FACING STATEMENT
                The user-facing problem statement fields MUST be written in Vietnamese:
                - formatted_description: Vietnamese only, preserving formulas, variable names, and math notation.
                - input_format: Vietnamese only, clearly explaining every input line/token.
                - output_format: Vietnamese only, clearly explaining what to print.
                Keep machine-readable fields such as input_schema, code, identifiers, and JSON keys unchanged.
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

                Return EXACTLY this JSON structure:
                {
                  "formatted_description": "Mô tả bài toán đã được trình bày bằng tiếng Việt, chia đoạn bằng \\n\\n",
                  "understanding": "Brief summary of the problem logic",
                  "input_format": "Mô tả chi tiết định dạng dữ liệu vào bằng tiếng Việt",
                  "output_format": "Mô tả chi tiết định dạng dữ liệu ra bằng tiếng Việt",
                  "constraints": "Các ràng buộc",
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
                    "problem_type": "short category",
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
                  "generator_code": "",
                  "golden_solution": "",
                  "bruteforce_solution": "",
                  "bruteforce_language": "cpp",
                  "validator_rules": ["rule 1", "rule 2"],
                  "generation_strategy": {
                    "small_cases": true,
                    "random_cases": true,
                    "edge_cases": true,
                    "stress_cases": true
                  },
                  "edge_cases": [
                    {"input": "small input", "expected_output": "expected output", "is_sample": true}
                  ]
                }

                test_plan requirements:
                - Provide enough normalized detail for a separate local C++ generator model.
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
                - For each scalar/array/matrix value include numeric min/max when known.
                - Use length references such as "N", "M", "Q", "N-1" only when that scalar appears earlier.
                - For graph-like data include node indexing, directedness, self-loop policy, multi-edge policy, and every column.
                - If the statement is complex but specified, provide the safest broad schema that always generates valid input.
                - If the statement is missing required format/constraint facts, mark them as "unknown"; do not invent them.
                
                edge_cases: at most 3 tiny manually written cases. No huge raw data.
                checker_code: empty string for unique-output problems.
                wrong_solutions requirements:
                - Provide at least 3 plausible wrong solutions when feasible: overflow, greedy, boundary/off-by-one.
                - Each wrong solution must be executable code, not prose.
                - Keep wrong solutions concise but complete.
                - Use exact type names from this set whenever relevant: overflow, greedy, boundary, off_by_one, brute_force, tle.
                - If bug_classes includes overflow, you must include at least one executable wrong_solution with type="overflow".
                - If bug_classes includes greedy, you must include at least one executable wrong_solution with type="greedy".
                - If a slow brute-force approach is plausible, include one executable wrong_solution with type="tle" or "brute_force".
                - For each executable wrong_solution, set killed_by_profiles to the most likely testcase profiles that should defeat it.
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
                - formatted_description, input_format, and output_format must not be English prose.
                - If the original statement is English, translate those three fields to Vietnamese.
                - Do not translate variable names, constants, sample input/output data, or source code.
                """.formatted(
                problemText == null ? "" : problemText,
                analysisJson == null ? "{}" : analysisJson,
                count);
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
            dto.setFormattedDescription(root.path("formatted_description").asText(""));
            dto.setInputFormat(root.path("input_format").asText(root.path("input").asText("")));
            dto.setOutputFormat(root.path("output_format").asText(root.path("output").asText("")));
            dto.setConstraints(root.path("constraints").asText(""));
            JsonNode inputSchemaNode = root.path("input_schema");
            if (!inputSchemaNode.isMissingNode() && !inputSchemaNode.isNull()) {
                dto.setInputSchema(inputSchemaNode.deepCopy());
            }
            dto.setCheckerCode(root.path("checker_code").asText(""));
            dto.setValidatorCode(readCodeField(root, "validator_code"));
            dto.setTotalTestcases(root.path("total_testcases").asInt(10));
            dto.setGeneratorCode(root.path("generator_code").asText(""));
            dto.setGeneratorLanguage(root.path("generator_language").asText("python"));
            dto.setGoldenSolution(readCodeField(root, "golden_solution"));
            dto.setBruteForceSolution(readCodeField(root, "bruteforce_solution"));
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
                    return rootNode.path("candidates").get(0)
                            .path("content").path("parts").get(0)
                            .path("text").asText();
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
                    break;
                }
            }
        }

        throw new RuntimeException(errorPrefix + " thất bại với toàn bộ Gemini API keys đã cấu hình. "
                + "Hãy kiểm tra quota/key hoặc thêm key khác vào GEMINI_API_KEYS.",
                lastFailure);
    }

    private String geminiUrl(String apiKey) {
        return "https://generativelanguage.googleapis.com/v1beta/models/"
                + geminiProModel + ":generateContent?key=" + apiKey;
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

    private boolean isHardQuotaExceeded(String responseBody) {
        if (responseBody == null) return false;
        String lower = responseBody.toLowerCase();
        return lower.contains("quota exceeded")
                && (lower.contains("limit: 0") || lower.contains("exceeded your current quota"));
    }
}
