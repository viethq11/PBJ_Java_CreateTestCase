package com.pbj.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.pbj.dto.AiResponseDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiTestGenerationServiceTest {

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
}
