package com.pbj.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.pbj.dto.AiResponseDTO;
import com.pbj.entity.AiCache;
import com.pbj.repository.AiCacheRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AiIntegrationService {

    private final AiCacheRepository aiCacheRepository;

    @Value("${ai.gemini.api-key}")
    private String geminiApiKey;

    @Value("${ai.gemini.model}")
    private String geminiModel;

    @Value("${ai.gemini.pro-model:gemini-2.5-pro}")
    private String geminiProModel;

    @Value("${ai.gemini.timeout-seconds:120}")
    private int timeoutSeconds;

    @Value("${ai.openai.base-url}")
    private String openAiBaseUrl;

    @Value("${ai.openai.api-key}")
    private String openAiApiKey;

    @Value("${ai.openai.model}")
    private String openAiModel;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            // Chấp nhận tên field không có ngoặc kép
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            // Chấp nhận ngoặc đơn (single quotes) thay vì ngoặc kép
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            // Chấp nhận các ký tự enter/tab rác lọt vào chuỗi
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            // Chấp nhận mọi dấu backslash thừa
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            // Không throw lỗi nếu JSON có field lạ mà DTO không có
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    // =====================================================================
    // PUBLIC: Generate full analysis (generator code + golden solution)
    // =====================================================================

    public AiResponseDTO generateTestCases(String problemDescription, List<String> base64Images, int count) {
        return generateTestCases(problemDescription, base64Images, count, false);
    }

    public AiResponseDTO generateTestCases(String problemDescription, List<String> base64Images, int count, boolean bypassCache) {
        String cacheKey = "openai_analysis_" + generateHash(problemDescription
                + (base64Images != null ? String.join("", base64Images) : "") + count);
        Optional<AiCache> cached = aiCacheRepository.findByRequestHash(cacheKey);

        if (!bypassCache && cached.isPresent()) {
            try {
                System.out.println("INFO: AI Cache Hit for analysis. Hash: " + cacheKey);
                return objectMapper.readValue(cached.get().getResponse(), AiResponseDTO.class);
            } catch (Exception e) {
                System.err.println("WARN: Failed to read from AI Cache: " + e.getMessage());
            }
        }

        // Luồng xử lý:
        // - Có ảnh: Gemini đọc ảnh → trích xuất text đề bài → OpenAI phân tích + sinh code
        // - Không có ảnh: OpenAI phân tích trực tiếp
        AiResponseDTO dto;
        if (base64Images != null && !base64Images.isEmpty()) {
            dto = callImagePipeline(problemDescription, base64Images, count);
        } else {
            dto = callOpenAiForFullAnalysis(problemDescription, count);
        }

        // Persist to cache
        try {
            AiCache cacheEntry = cached.orElse(new AiCache());
            cacheEntry.setRequestHash(cacheKey);
            cacheEntry.setResponse(objectMapper.writeValueAsString(dto));
            aiCacheRepository.save(cacheEntry);
        } catch (Exception e) {
            System.err.println("WARN: Failed to save to AI Cache: " + e.getMessage());
        }

        return dto;
    }

    // =====================================================================
    // PRIVATE: 2-step pipeline for image input
    // Step 1: Gemini reads image and extracts problem text
    // Step 2: OpenAI performs full analysis + code generation on extracted text
    // =====================================================================

    private AiResponseDTO callImagePipeline(String problemDescription, List<String> base64Images, int count) {
        System.out.println("INFO: [Image Pipeline] Step 1 - Gemini extracting problem text from image...");
        String extractedText = callGeminiToExtractText(problemDescription, base64Images);

        if (extractedText == null || extractedText.isBlank()) {
            System.err.println("WARN: [Image Pipeline] Gemini failed to extract text, falling back to Groq with placeholder.");
            extractedText = problemDescription != null ? problemDescription : "(problem from attached image)";
        } else {
            System.out.println("INFO: [Image Pipeline] Step 1 complete. Extracted " + extractedText.length() + " chars.");
        }

        System.out.println("INFO: [Image Pipeline] Step 2 - OpenAI analyzing extracted text and generating code...");
        return callOpenAiForFullAnalysis(extractedText, count);
    }

    // =====================================================================
    // PRIVATE: Gemini — Step 1: extract plain text from image (OCR)
    // =====================================================================

    private String callGeminiToExtractText(String hint, List<String> base64Images) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + geminiProModel + ":generateContent?key=" + geminiApiKey;

        String ocrPrompt = """
                You are an expert OCR system for competitive programming problems.
                Read the attached image(s) carefully and extract the COMPLETE problem statement as plain text.
                Preserve all mathematical formulas using LaTeX notation ($...$ or $$...$$).
                Preserve all constraints, examples, and formatting.
                Output ONLY the extracted problem text. No explanations, no JSON, just the raw problem text.
                """
                + (hint != null && !hint.isBlank() ? "\nAdditional context from user: " + hint : "");

        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", ocrPrompt));

        if (base64Images != null) {
            for (String b64 : base64Images) {
                parts.add(Map.of("inline_data", Map.of(
                        "mime_type", "image/jpeg",
                        "data", b64
                )));
            }
        }

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of("parts", parts))
        );

        return executeGeminiRequest(url, requestBody, "Gemini (OCR Extract)", 5000L);
    }

    // =====================================================================
    // PRIVATE: OpenAI — full analysis (text only)
    // =====================================================================

    private AiResponseDTO callOpenAiForFullAnalysis(String problemDescription, int count) {
        String url = openAiBaseUrl + "/chat/completions";
        String prompt = buildAnalysisPrompt(problemDescription, count);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openAiApiKey);

        Map<String, Object> requestBody = Map.of(
                "model", openAiModel,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        // Exponential backoff: 5s / 10s / 20s / 40s / 80s
        int maxRetries = 5;
        long[] backoffMs = {5000L, 10000L, 20000L, 40000L, 80000L};

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);
                ResponseEntity<String> response = buildRestTemplate().postForEntity(url, entity, String.class);

                JsonNode rootNode = objectMapper.readTree(response.getBody());
                String responseText = rootNode.path("choices").get(0).path("message").path("content").asText();
                return parseAnalysisResponse(responseText);

            } catch (HttpServerErrorException.ServiceUnavailable | HttpClientErrorException.TooManyRequests e) {
                if (attempt == maxRetries) {
                    throw new RuntimeException("OpenAI API (Analysis) đang quá tải sau " + maxRetries + " lần thử.", e);
                }
                long sleepTime = backoffMs[attempt - 1];
                System.err.println("!!! RATE LIMIT !!! OpenAI Analysis. Sleeping " + (sleepTime / 1000) + "s before retry "
                        + (attempt + 1) + "/" + maxRetries);
                try { Thread.sleep(sleepTime); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

            } catch (Exception e) {
                throw new RuntimeException("OpenAI Analysis Failed: " + e.getMessage(), e);
            }
        }
        return new AiResponseDTO();
    }

    // =====================================================================
    // PRIVATE: Shared prompt builder
    // =====================================================================

    private String buildAnalysisPrompt(String problemDescription, int count) {
        return """
                You are an expert Competitive Programming problem analyst (ICPC/Codeforces level).
                Your task is to analyze the problem below and return a JSON response.
                
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                ABSOLUTE RULE #1 — NEVER GENERATE HUGE RAW TESTCASES
                ❌ NEVER: output large arrays, generate N=100000 test lines, or compute expected output manually.
                
                ABSOLUTE RULE #2 — GENERATE CODE, NOT DATA
                ✅ ALWAYS: Write a generator script (Python preferred, C++ if needed).
                ✅ ALWAYS: Write a correct golden solution in C++.
                ✅ ALWAYS: Write a structured adversarial test plan BEFORE writing generator logic.
                ✅ ALWAYS: The generator must contain named test-family functions that target likely wrong solutions.
                ✅ ALWAYS: Include complexity probes that kill brute-force/TLE approaches under the stated max constraints.
                ✅ ALWAYS: Use boundary numeric values near min/max constraints (for example 10^9, -10^9, 64-bit sums) when the problem has numeric arrays or weights.
                
                ABSOLUTE RULE #3 — JSON SYNTAX & ESCAPING (CRITICAL)
                - Output ONLY a valid JSON object. No conversational text.
                - You MAY use actual line breaks (Enter key) to format the JSON structure itself (between keys, between array items, etc.).
                - CRITICAL: INSIDE string values (like generator_code, golden_solution, formatted_description), NEVER use actual line breaks. You MUST represent newlines using the literal two-character sequence \\n.
                - CRITICAL: INSIDE string values, ALL backslashes MUST be double-escaped. For example, you MUST write \\\\le instead of \\le.
                - CRITICAL: INSIDE string values, ALL double-quotes MUST be escaped as \\".
                
                ABSOLUTE RULE #4 — LANGUAGE & FORMATTING
                - ALL descriptive text fields (formatted_description, understanding, input_format, output_format, constraints) MUST be in the EXACT SAME LANGUAGE as the provided 'Problem' text.
                - If the problem text is in Vietnamese, all these fields MUST be in Vietnamese.
                - 'formatted_description' must use \\n\\n for paragraphs and strictly preserve LaTeX enclosed in $...$ or $$...$$.
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                
                Return EXACTLY this JSON structure (keys must match exactly):
                {
                  "formatted_description": "The beautifully formatted problem description with \\n\\n for paragraphs and preserved LaTeX",
                  "understanding": "Brief summary of the problem logic",
                  "input_format": "Detailed input format description",
                  "output_format": "Detailed output format description",
                  "constraints": "Constraints e.g. 1 <= N <= 100000",
                  "checker_code": "Java Checker code if special judge needed, else empty string",
                  "validator_code": "Python validator code that checks input format and constraints, else empty string",
                  "test_plan": {
                    "problem_type": "short machine-readable category",
                    "intended_solution": "Core correct algorithm and complexity",
                    "wrong_solutions": [
                      {
                        "name": "likely_wrong_approach_name",
                        "why_wrong": "Why this solution fails",
                        "counterexample_strategy": "How to generate cases that expose it"
                      }
                    ],
                    "test_families": [
                      {
                        "name": "family_name_used_by_generator",
                        "difficulty": "small|medium|large|stress",
                        "target": ["wrong_solution_name"],
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
                  "generator_language": "python",
                  "generator_code": "Full Python generator script with argparse --seed INT --size STR",
                  "golden_solution": "Complete correct C++ solution (compilable, no pseudocode)",
                  "validator_rules": ["1 <= N <= 100000", "values in range"],
                  "generation_strategy": {
                    "small_cases": true,
                    "random_cases": true,
                    "edge_cases": true,
                    "stress_cases": true
                  },
                  "edge_cases": [
                    {"input": "1\\n1", "expected_output": "1", "is_sample": true}
                  ]
                }
                
                CRITICAL NOTES:
                - generator_code: Full runnable Python script. Must accept --seed INT and --size (small|medium|large|stress).
                  small: tiny/bruteforce-checkable cases.
                  medium: moderately random cases.
                  large: near-large but not necessarily maximum cases.
                  stress: MUST use the largest relevant constraints from the statement whenever output size permits.
                  It MUST define several named test-family functions matching test_plan.test_families and choose among them using seed and size.
                  It MUST include adversarial families for boundary cases, weak/random cases, and complexity/TLE traps when applicable.
                  For each common slow wrong approach, include at least one stress family that makes its time complexity explode.
                  Examples:
                  - Sliding window / fixed-length max subarray: include n at max, k around n/2, values near +/-1e9 to kill O(n*k) and int overflow.
                  - Graph reachability with n,m up to 2e5: include long chains, reverse chains, layered DAGs, many useless components, and max-size sparse traps to kill O(n*m), recursion-depth, and undirected-assumption solutions.
                  - DP/knapsack/grid problems: include dimensions at max and values that force the intended optimized state space.
                - golden_solution: Must be valid compilable C++17. No pseudocode, no placeholders.
                - test_plan.wrong_solutions: list 5 to 10 likely wrong approaches when the problem is non-trivial, including at least one brute-force/TLE approach.
                - test_plan.test_families: every family must target at least one wrong solution or a boundary/performance risk.
                  At least 30%% of families must be stress/complexity families for non-trivial constraints.
                - validator_code: runnable Python 3 validator that reads stdin and exits non-zero on invalid input; keep it concise.
                - edge_cases: ONLY small manually crafted cases (N<=20, at most 3 cases). No huge arrays.
                - checker_code: Leave as empty string "" for problems with unique answers.
                
                Problem:
                %s
                """.formatted(count, problemDescription == null ? "(see attached image)" : problemDescription);
    }

    // =====================================================================
    // PRIVATE: Parse AI JSON response into AiResponseDTO
    // =====================================================================

    private AiResponseDTO parseAnalysisResponse(String responseText) {
        if (responseText == null || responseText.isBlank()) return new AiResponseDTO();

        String cleanedText = stripMarkdownFences(responseText); // Bóc tách Markdown
        
        // --- Dọn rác: xóa các cụm \n literal nằm cạnh dấu ngoặc cấu trúc JSON ---
        // AI đôi khi in {\ n thay vì newline thực sự, khiến Jackson báo lỗi
        cleanedText = cleanedText.replace("{\\n", "{")
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

            // Validator rules
            JsonNode validatorNode = root.path("validator_rules");
            if (validatorNode.isArray()) {
                List<String> rules = new ArrayList<>();
                validatorNode.forEach(n -> rules.add(n.asText()));
                dto.setValidatorRules(rules);
            }

            // Generation strategy
            JsonNode stratNode = root.path("generation_strategy");
            if (!stratNode.isMissingNode()) {
                AiResponseDTO.GenerationStrategy strat = new AiResponseDTO.GenerationStrategy();
                strat.setSmallCases(stratNode.path("small_cases").asBoolean(true));
                strat.setRandomCases(stratNode.path("random_cases").asBoolean(true));
                strat.setEdgeCases(stratNode.path("edge_cases").asBoolean(true));
                strat.setStressCases(stratNode.path("stress_cases").asBoolean(true));
                dto.setGenerationStrategy(strat);
            }

            // Manually crafted edge cases (small)
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
            // IN RAW TEXT RA ĐỂ DEBUG
            System.err.println("\n=== RAW AI RESPONSE (FOR DEBUGGING) ===");
            System.err.println(cleanedText);
            System.err.println("=======================================\n");
            
            throw new RuntimeException("Failed to parse AI analysis response: " + e.getMessage(), e);
        }
    }

    // =====================================================================
    // PUBLIC: Code generation methods (AC / WA / TLE)
    // =====================================================================

    public String generateAcceptedCode(String title, String description, String inputFormat, String outputFormat, String constraints, String language) {
        return generateCodeWithCache(title, description, inputFormat, outputFormat, constraints, language, "AC");
    }

    public String generateWrongAnswerCode(String title, String description, String inputFormat, String outputFormat, String constraints, String language) {
        return generateCodeWithCache(title, description, inputFormat, outputFormat, constraints, language, "WA");
    }

    public String generateTimeLimitExceededCode(String title, String description, String inputFormat, String outputFormat, String constraints, String language) {
        return generateCodeWithCache(title, description, inputFormat, outputFormat, constraints, language, "TLE");
    }

    private String generateCodeWithCache(String title, String description, String inputFormat, String outputFormat, String constraints, String language, String type) {
        String cacheKey = "code_" + type + "_" + generateHash(title + description + inputFormat + outputFormat + constraints + language);
        Optional<AiCache> cached = aiCacheRepository.findByRequestHash(cacheKey);
        if (cached.isPresent()) {
            try {
                System.out.println("INFO: AI Cache Hit for code. Hash: " + cacheKey);
                return objectMapper.readValue(cached.get().getResponse(), String.class);
            } catch (Exception e) {
                System.err.println("WARN: Failed to read code from AI Cache: " + e.getMessage());
            }
        }

        String code = generateCodeWithGemini(title, description, inputFormat, outputFormat, constraints, language, type);
        try {
            AiCache cacheEntry = new AiCache();
            cacheEntry.setRequestHash(cacheKey);
            cacheEntry.setResponse(objectMapper.writeValueAsString(code));
            aiCacheRepository.save(cacheEntry);
        } catch (Exception e) {
            System.err.println("WARN: Failed to save generated code to AI Cache: " + e.getMessage());
        }
        return code;
    }

    private String generateCodeWithGemini(String title, String description, String inputFormat, String outputFormat, String constraints, String language, String type) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + geminiProModel + ":generateContent?key=" + geminiApiKey;

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

        String responseText = executeGeminiRequest(url, requestBody, "Gemini (Code:" + type + ")", 5000L);
        return stripMarkdownFences(responseText);
    }

    // =====================================================================
    // PRIVATE: HTTP helpers
    // =====================================================================

    private String executeGeminiRequest(String url, Map<String, Object> requestBody, String errorPrefix, long firstBackoffMs) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Exponential backoff: firstBackoffMs, 2x, 4x, 8x, 16x
        int maxRetries = 5;
        long sleepMs = firstBackoffMs;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);
                ResponseEntity<String> response = buildRestTemplate().postForEntity(url, entity, String.class);

                JsonNode rootNode = objectMapper.readTree(response.getBody());
                return rootNode.path("candidates").get(0)
                        .path("content").path("parts").get(0)
                        .path("text").asText();

            } catch (HttpServerErrorException.ServiceUnavailable | HttpClientErrorException.TooManyRequests e) {
                if (attempt == maxRetries) {
                    throw new RuntimeException(errorPrefix + " API đang quá tải sau " + maxRetries + " lần thử.", e);
                }
                System.err.println("!!! RATE LIMIT !!! " + errorPrefix + ". Sleeping " + (sleepMs / 1000)
                        + "s before retry " + (attempt + 1) + "/" + maxRetries);
                try { Thread.sleep(sleepMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                sleepMs *= 2; // exponential backoff

            } catch (Exception e) {
                throw new RuntimeException(errorPrefix + " Failed: " + e.getMessage(), e);
            }
        }
        return null;
    }

    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutSeconds * 1000);
        factory.setReadTimeout(timeoutSeconds * 1000);
        return new RestTemplate(factory);
    }

    private String generateHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new RuntimeException("Could not generate hash", e);
        }
    }

    private String stripMarkdownFences(String text) {
        if (text == null) return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?s)```(?:\\w+)?\\s*\\n?(.*?)```");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return text.trim();
    }
}
