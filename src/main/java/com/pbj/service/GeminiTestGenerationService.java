package com.pbj.service;

import com.fasterxml.jackson.core.json.JsonReadFeature;
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
import java.util.List;
import java.util.Map;

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
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))
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
                Use BOTH. Trust the original problem if there is any conflict.

                Original problem:
                %s

                analysis_json:
                %s

                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                ABSOLUTE RULE #1 — NEVER GENERATE HUGE RAW TESTCASES
                NEVER output large arrays, N=100000 raw lines, or manually computed expected outputs.

                ABSOLUTE RULE #2 — GENERATE CODE, NOT DATA
                ALWAYS write golden_solution, validator_code, and test_plan.
                Leave generator_code as an empty string. A local Ollama service will write the generator from your normalized spec.

                ABSOLUTE RULE #3 — ADVERSARIAL TESTING
                Include named test-family functions that target likely wrong solutions from analysis_json.
                Include complexity probes that kill brute-force/TLE approaches under max constraints.
                Use numeric extremes near min/max constraints, e.g. 10^9, -10^9, and 64-bit sums.

                ABSOLUTE RULE #4 — JSON SYNTAX & ESCAPING
                Output ONLY a valid JSON object. No markdown.
                Inside string values, represent newlines using literal \\n.
                Inside string values, escape all double-quotes as \\" and all backslashes as \\\\.
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

                Return EXACTLY this JSON structure:
                {
                  "formatted_description": "Formatted problem statement with \\n\\n paragraphs",
                  "understanding": "Brief summary of the problem logic",
                  "input_format": "Detailed input format description",
                  "output_format": "Detailed output format description",
                  "constraints": "Constraints",
                  "checker_code": "Java Checker code if special judge needed, else empty string",
                  "validator_code": "Complete Python 3 validator. Read stdin, assert input format/constraints, exit non-zero on invalid input.",
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
                  "total_testcases": %d,
                  "generator_language": "cpp",
                  "generator_code": "",
                  "golden_solution": "Complete correct C++17 solution",
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
                - For graph n,m up to 2e5, specify valid indexing, directedness, self-loop/multi-edge policy, edge weights/types, and max sparse traps.
                - For array/window/sum problems, specify numeric min/max, n max, k-sensitive cases, and values near +/-1e9 when allowed.
                
                edge_cases: at most 3 tiny manually written cases. No huge raw data.
                checker_code: empty string for unique-output problems.
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
            JsonNode root = objectMapper.readTree(cleanedText);
            AiResponseDTO dto = new AiResponseDTO();
            dto.setUnderstanding(root.path("understanding").asText(""));
            dto.setFormattedDescription(root.path("formatted_description").asText(""));
            dto.setInputFormat(root.path("input_format").asText(root.path("input").asText("")));
            dto.setOutputFormat(root.path("output_format").asText(root.path("output").asText("")));
            dto.setConstraints(root.path("constraints").asText(""));
            dto.setCheckerCode(root.path("checker_code").asText(""));
            dto.setValidatorCode(root.path("validator_code").asText(""));
            dto.setTotalTestcases(root.path("total_testcases").asInt(10));
            dto.setGeneratorCode(root.path("generator_code").asText(""));
            dto.setGeneratorLanguage(root.path("generator_language").asText("python"));
            dto.setGoldenSolution(root.path("golden_solution").asText(""));

            JsonNode testPlanNode = root.path("test_plan");
            if (!testPlanNode.isMissingNode() && !testPlanNode.isNull()) {
                dto.setTestPlan(objectMapper.treeToValue(testPlanNode, AiResponseDTO.TestPlan.class));
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
