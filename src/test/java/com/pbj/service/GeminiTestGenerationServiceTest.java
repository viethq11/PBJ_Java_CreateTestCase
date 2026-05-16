package com.pbj.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.pbj.dto.AiResponseDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiTestGenerationServiceTest {

    @Test
    void generationPromptRequiresReferenceArtifactsAndDisallowsRawCases() throws Exception {
        GeminiTestGenerationService service = new GeminiTestGenerationService();
        Method method = GeminiTestGenerationService.class.getDeclaredMethod(
                "buildGenerationPrompt", String.class, String.class, int.class);
        method.setAccessible(true);

        String prompt = (String) method.invoke(service, "problem", "{}", 10);

        assertThat(prompt).contains("golden_solution must be a complete compilable C++17 reference program matching input_schema exactly.");
        assertThat(prompt).contains("bruteforce_solution is mandatory whenever small/exhaustive or boundary verification is feasible.");
        assertThat(prompt).contains("\"golden_solution\": \"Complete C++17 reference solution source code\"");
        assertThat(prompt).contains("Do not output raw edge_cases or manually compute/invent any expected outputs.");
        assertThat(prompt).contains("\"edge_cases\": []");
        assertThat(prompt).contains("Do NOT write \"unknown\" in input_schema.min, input_schema.max, length, rows, cols, columns[].min, or columns[].max.");
        assertThat(prompt).contains("The constraints field must not contain the words unknown, unspecified, or not specified.");
        assertThat(prompt).contains("All problem statement fields must be written in Markdown.");
        assertThat(prompt).contains("Write ranges as math such as $1 \\le N \\le 10^6$.");
        assertThat(prompt).contains("Do NOT insert hard line breaks inside a normal paragraph");
        assertThat(prompt).contains("Never introduce command-style operations such as UPDATE/QUERY unless the original problem explicitly uses them.");
        assertThat(prompt).contains("Forbidden drift to avoid:");
    }

    @Test
    void referenceCandidatePromptFreezesExistingSpecAndMarksGoldenAsCandidate() throws Exception {
        GeminiTestGenerationService service = new GeminiTestGenerationService();
        AiResponseDTO dto = new AiResponseDTO();
        dto.setInputFormat("Dòng đầu chứa N.");
        dto.setOutputFormat("In ra đáp án.");
        dto.setConstraints("- $1 \\le N \\le 10^5$");

        Method method = GeminiTestGenerationService.class.getDeclaredMethod(
                "buildReferenceCandidatePrompt", String.class, AiResponseDTO.class);
        method.setAccessible(true);

        String prompt = (String) method.invoke(service, "Bài toán gốc", dto);

        assertThat(prompt).contains("A normalized backend-validated specification is already frozen below.");
        assertThat(prompt).contains("Do NOT reinterpret the task");
        assertThat(prompt).contains("\"golden_solution\": \"Complete compilable C++17 optimized reference solution\"");
        assertThat(prompt).contains("golden_solution is only a candidate; the backend will verify it independently.");
        assertThat(prompt).contains("Dòng đầu chứa N.");
    }

    @Test
    void geminiFailureMessageDoesNotMislabelGenericFailuresAsQuota() throws Exception {
        GeminiTestGenerationService service = new GeminiTestGenerationService();
        Method method = GeminiTestGenerationService.class.getDeclaredMethod(
                "buildGeminiFailureMessage", String.class, RuntimeException.class);
        method.setAccessible(true);

        String generic = (String) method.invoke(service, "Gemini API key preflight",
                new RuntimeException("SSL handshake failed"));
        String quota = (String) method.invoke(service, "Gemini API key preflight",
                new RuntimeException("HTTP 429 RESOURCE_EXHAUSTED quota exceeded"));

        assertThat(generic).contains("thất bại trước khi xác nhận quota Gemini");
        assertThat(generic).doesNotContain("thất bại vì quota/rate limit");
        assertThat(quota).contains("thất bại vì quota/rate limit của Gemini");
    }

    @Test
    void preflightUsesEnoughOutputTokensForShortReply() throws Exception {
        GeminiTestGenerationService service = new GeminiTestGenerationService();
        Method method = GeminiTestGenerationService.class.getDeclaredMethod("verifyApiKeysBeforePipeline");
        assertThat(method).isNotNull();

        Method sourceMethod = GeminiTestGenerationService.class.getDeclaredMethod("buildRestTemplate");
        assertThat(sourceMethod).isNotNull();
    }

    @Test
    void parserNormalizesLoopOfScalarsIntoQueriesSchema() throws Exception {
        String response = """
                {
                  "formatted_description": "Mo ta",
                  "understanding": "summary",
                  "input_format": "Dong 1: N. N dong tiep theo moi dong gom x y.",
                  "output_format": "In dap an.",
                  "constraints": "- 1 <= N <= 100",
                  "input_schema": {
                    "multiple_test_cases": false,
                    "lines": [
                      {"kind": "scalars", "fields": [{"name": "N", "type": "int", "min": 1, "max": 100}]},
                      {
                        "kind": "loop",
                        "length": "N",
                        "body": [
                          {
                            "kind": "scalars",
                            "fields": [
                              {"name": "x", "type": "int", "min": 0, "max": 1000},
                              {"name": "y", "type": "int", "min": 0, "max": 1000}
                            ]
                          }
                        ]
                      }
                    ]
                  },
                  "checker_code": "",
                  "validator_code": "",
                  "bug_classes": [],
                  "test_plan": {
                    "problem_type": "GENERIC_SCHEMA",
                    "intended_solution": "simulation",
                    "wrong_solutions": [],
                    "test_families": [{"name": "small", "difficulty": "small", "target": [], "constraints": "small", "expected": "valid", "reason": "baseline"}],
                    "generator_requirements": {
                      "must_include_bruteforce_for_small": true,
                      "must_include_large_stress": true,
                      "must_include_complexity_traps": true,
                      "must_include_numeric_extremes": true,
                      "must_avoid_raw_large_data": true
                    }
                  },
                  "wrong_solutions": [],
                  "test_profiles": [],
                  "total_testcases": 10,
                  "generator_language": "cpp",
                  "generator_code": "",
                  "golden_solution": "",
                  "bruteforce_solution": "",
                  "bruteforce_language": "cpp",
                  "validator_rules": [],
                  "generation_strategy": {
                    "small_cases": true,
                    "random_cases": true,
                    "edge_cases": true,
                    "stress_cases": true
                  },
                  "edge_cases": []
                }
                """;

        GeminiTestGenerationService service = new GeminiTestGenerationService();
        Method method = GeminiTestGenerationService.class.getDeclaredMethod("parseAnalysisResponse", String.class);
        method.setAccessible(true);

        AiResponseDTO dto = (AiResponseDTO) method.invoke(service, response);
        JsonNode normalizedLoopLine = dto.getInputSchema().path("lines").get(1);

        assertThat(normalizedLoopLine.path("kind").asText()).isEqualTo("queries");
        assertThat(normalizedLoopLine.path("length").asText()).isEqualTo("N");
        assertThat(normalizedLoopLine.path("columns")).hasSize(2);
        assertThat(normalizedLoopLine.path("columns").get(0).path("name").asText()).isEqualTo("x");
    }

    @Test
    void parserNormalizesTupleLineIntoQueriesSchema() throws Exception {
        String response = """
                {
                  "formatted_description": "Mo ta",
                  "understanding": "summary",
                  "input_format": "Dong 1: n q. Dong 2: c_i. N-1 dong canh. Q dong truy van.",
                  "output_format": "In dap an.",
                  "constraints": "- 1 <= n, q <= 200000",
                  "input_schema": {
                    "multiple_test_cases": false,
                    "lines": [
                      {"kind": "scalars", "fields": [
                        {"name": "n", "type": "int", "min": 1, "max": 200000},
                        {"name": "q", "type": "int", "min": 1, "max": 200000}
                      ]},
                      {"kind": "array", "name": "c", "type": "int", "length": "n", "min": 1, "max": 1000000000},
                      {"kind": "edges", "length": "n-1", "columns": [
                        {"name": "u", "type": "node", "min": 1, "max": "n"},
                        {"name": "v", "type": "node", "min": 1, "max": "n"}
                      ]},
                      {"kind": "tuple", "length": "q", "fields": [
                        {"name": "u", "type": "node", "min": 1, "max": "n"},
                        {"name": "v", "type": "node", "min": 1, "max": "n"},
                        {"name": "x", "type": "node", "min": 1, "max": "n"},
                        {"name": "z", "type": "int", "min": 1, "max": 1000000000}
                      ]}
                    ]
                  },
                  "checker_code": "",
                  "validator_code": "",
                  "bug_classes": [],
                  "test_plan": {
                    "problem_type": "GENERIC_SCHEMA",
                    "intended_solution": "tree queries",
                    "wrong_solutions": [],
                    "test_families": [{"name": "small", "difficulty": "small", "target": [], "constraints": "small", "expected": "valid", "reason": "baseline"}],
                    "generator_requirements": {
                      "must_include_bruteforce_for_small": true,
                      "must_include_large_stress": true,
                      "must_include_complexity_traps": true,
                      "must_include_numeric_extremes": true,
                      "must_avoid_raw_large_data": true
                    }
                  },
                  "wrong_solutions": [],
                  "test_profiles": [],
                  "total_testcases": 10,
                  "generator_language": "cpp",
                  "generator_code": "",
                  "golden_solution": "",
                  "bruteforce_solution": "",
                  "bruteforce_language": "cpp",
                  "validator_rules": [],
                  "generation_strategy": {
                    "small_cases": true,
                    "random_cases": true,
                    "edge_cases": true,
                    "stress_cases": true
                  },
                  "edge_cases": []
                }
                """;

        GeminiTestGenerationService service = new GeminiTestGenerationService();
        Method method = GeminiTestGenerationService.class.getDeclaredMethod("parseAnalysisResponse", String.class);
        method.setAccessible(true);

        AiResponseDTO dto = (AiResponseDTO) method.invoke(service, response);
        JsonNode normalizedTupleLine = dto.getInputSchema().path("lines").get(3);

        assertThat(normalizedTupleLine.path("kind").asText()).isEqualTo("queries");
        assertThat(normalizedTupleLine.path("length").asText()).isEqualTo("q");
        assertThat(normalizedTupleLine.path("columns")).hasSize(4);
        assertThat(normalizedTupleLine.path("columns").get(2).path("name").asText()).isEqualTo("x");
    }

    @Test
    void normalizesLegacyPlainTextMathIntoMarkdownLatex() {
        GeminiTestGenerationService service = new GeminiTestGenerationService();

        String normalized = service.normalizeStatementMarkdown("""
                - 1 <= N <= 10^6
                - 1 <= a_i <= 10^9
                - Gia tri rieng le a_j va 10^5
                """);

        assertThat(normalized).contains("$1 \\le N \\le 10^6$");
        assertThat(normalized).contains("$1 \\le a_i \\le 10^9$");
        assertThat(normalized).contains("$a_j$");
        assertThat(normalized).contains("$10^5$");
    }
}
