package com.pbj.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbj.dto.AiResponseDTO;
import com.pbj.dto.SemanticSpecDTO;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticArtifactCompilerServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SemanticArtifactCompilerService service = new SemanticArtifactCompilerService(objectMapper);

    @Test
    void compilesCommandModelIntoMissingSchemaPlanAndGenerator() throws Exception {
        AiResponseDTO dto = new AiResponseDTO();
        SemanticSpecDTO spec = new SemanticSpecDTO();
        spec.setInputModel(objectMapper.readTree("""
                {
                  "type": "multi_test_command_based",
                  "test_count": "T",
                  "blocks": [
                    {"header": ["n", "m"]},
                    {"repeat": "m", "variants": [
                      {"keyword": "UPDATE", "args": ["x", "y", "z", "W"]},
                      {"keyword": "QUERY", "args": ["x1", "y1", "z1", "x2", "y2", "z2"]}
                    ]}
                  ]
                }
                """));
        spec.setConstraints(objectMapper.readTree("""
                {
                  "n": "1 <= n <= 100",
                  "m": "1 <= m <= 10^5",
                  "W": "-10^9 <= W <= 10^9"
                }
                """));
        dto.setSemanticSpec(spec);

        service.compileMissingArtifacts(dto);

        assertThat(dto.getInputSchema()).isNotNull();
        assertThat(dto.getInputSchema().path("multiple_test_cases").asBoolean()).isTrue();
        assertThat(dto.getInputSchema().path("lines").get(0).path("fields").get(0).path("name").asText()).isEqualTo("n");
        assertThat(dto.getInputSchema().path("lines").get(1).path("kind").asText()).isEqualTo("raw_lines");
        assertThat(dto.getTestPlan()).isNotNull();
        assertThat(dto.getGeneratorCode()).contains("UPDATE").contains("QUERY");
    }

    @Test
    void compilesMultiTestArrayModelIntoDeterministicArtifacts() throws Exception {
        AiResponseDTO dto = new AiResponseDTO();
        SemanticSpecDTO spec = new SemanticSpecDTO();
        spec.setInputModel(objectMapper.readTree("""
                {
                  "type": "multi_test",
                  "test_count": "T",
                  "blocks": [
                    {
                      "header": ["N"],
                      "variants": [
                        {"type": "array", "name": "a", "length": "N", "value_type": "integer"}
                      ]
                    }
                  ]
                }
                """));
        spec.setConstraints(objectMapper.readTree("""
                {
                  "N": "1 <= N <= 10^6",
                  "a_i": "1 <= a_i <= 10^9",
                  "a_i_distinct": "true"
                }
                """));
        dto.setSemanticSpec(spec);

        service.compileMissingArtifacts(dto);

        assertThat(dto.getInputSchema().path("multiple_test_cases").asBoolean()).isTrue();
        assertThat(dto.getInputSchema().path("lines").get(0).path("fields").get(0).path("name").asText())
                .isEqualTo("N");
        assertThat(dto.getInputSchema().path("lines").get(1).path("kind").asText()).isEqualTo("array");
        assertThat(dto.getTestPlan().getProblemType()).isEqualTo("generic_array_input");
        assertThat(dto.getGeneratorCode()).contains("unordered_set<long long> used");
    }

    @Test
    void compilesScalarModelIntoDeterministicArtifacts() throws Exception {
        AiResponseDTO dto = new AiResponseDTO();
        SemanticSpecDTO spec = new SemanticSpecDTO();
        spec.setInputModel(objectMapper.readTree("""
                {
                  "type": "single_case",
                  "blocks": [
                    {"header": ["n", "k"]}
                  ]
                }
                """));
        spec.setConstraints(objectMapper.readTree("""
                {
                  "n": "1 <= n <= 10^9",
                  "k": "1 <= k <= 10^4"
                }
                """));
        dto.setSemanticSpec(spec);

        service.compileMissingArtifacts(dto);

        assertThat(dto.getInputSchema().path("multiple_test_cases").asBoolean()).isFalse();
        assertThat(dto.getInputSchema().path("lines").get(0).path("fields")).hasSize(2);
        assertThat(dto.getTestPlan().getProblemType()).isEqualTo("generic_scalar_input");
        assertThat(dto.getGeneratorCode()).contains("long long n").contains("long long k");
    }

    @Test
    void compilesSectionStyleArrayModelIntoDeterministicArtifacts() throws Exception {
        AiResponseDTO dto = new AiResponseDTO();
        SemanticSpecDTO spec = new SemanticSpecDTO();
        spec.setInputModel(objectMapper.readTree("""
                {
                  "type": "multi_test",
                  "test_count": "T",
                  "sections": [
                    {
                      "header": ["N"],
                      "data": ["a"],
                      "data_type": "array_of_integers"
                    }
                  ]
                }
                """));
        spec.setConstraints(objectMapper.readTree("""
                {
                  "N": "1 <= N <= 10^6",
                  "a_i": "1 <= a_i <= 10^9",
                  "a_i_distinct": "true"
                }
                """));
        dto.setSemanticSpec(spec);

        service.compileMissingArtifacts(dto);

        assertThat(dto.getInputSchema().path("lines").get(1).path("name").asText()).isEqualTo("a");
        assertThat(dto.getGeneratorCode()).contains("unordered_set<long long> used");
    }

    @Test
    void compilesArrayStyleScalarSectionIntoDeterministicArtifacts() throws Exception {
        AiResponseDTO dto = new AiResponseDTO();
        SemanticSpecDTO spec = new SemanticSpecDTO();
        spec.setInputModel(objectMapper.readTree("""
                {
                  "type": "single_case",
                  "sections": [
                    ["n", "k"]
                  ]
                }
                """));
        spec.setConstraints(objectMapper.readTree("""
                {
                  "n": "1 <= n <= 10^9",
                  "k": "1 <= k <= 10^4"
                }
                """));
        dto.setSemanticSpec(spec);

        service.compileMissingArtifacts(dto);

        assertThat(dto.getInputSchema().path("lines").get(0).path("fields")).hasSize(2);
        assertThat(dto.getGeneratorCode()).contains("long long n").contains("long long k");
    }

    @Test
    void compilesLoopStyleCommandSectionsIntoDeterministicArtifacts() throws Exception {
        AiResponseDTO dto = new AiResponseDTO();
        SemanticSpecDTO spec = new SemanticSpecDTO();
        spec.setInputModel(objectMapper.readTree("""
                {
                  "type": "multi_test_command_based",
                  "test_count": "T",
                  "sections": [
                    {"read_variables": ["n", "m"]},
                    {
                      "loop": {
                        "count_variable": "m",
                        "body": [
                          {
                            "command_variants": [
                              {"keyword": "UPDATE", "args": ["x", "y", "z", "W"]},
                              {"keyword": "QUERY", "args": ["x1", "y1", "z1", "x2", "y2", "z2"]}
                            ]
                          }
                        ]
                      }
                    }
                  ]
                }
                """));
        spec.setConstraints(objectMapper.readTree("""
                {
                  "n": "1 <= n <= 100",
                  "m": "1 <= m <= 100000",
                  "W": "-10^9 <= W <= 10^9"
                }
                """));
        dto.setSemanticSpec(spec);

        service.compileMissingArtifacts(dto);

        assertThat(dto.getInputSchema().path("lines").get(0).path("fields")).hasSize(2);
        assertThat(dto.getInputSchema().path("lines").get(1).path("length").asText()).isEqualTo("m");
        assertThat(dto.getTestPlan().getProblemType()).isEqualTo("generic_command_stream");
        assertThat(dto.getGeneratorCode()).contains("UPDATE").contains("QUERY");
    }

    @Test
    void compilesFallbackArtifactsWhenUnrecognizedOrMissingModel() throws Exception {
        AiResponseDTO dto = new AiResponseDTO();
        SemanticSpecDTO spec = new SemanticSpecDTO();
        // Null or empty model representing unrecognized problem category
        spec.setInputModel(null);
        spec.setQueryVariables(List.of("R", "C"));
        dto.setSemanticSpec(spec);

        service.compileMissingArtifacts(dto);

        assertThat(dto.getInputSchema()).isNotNull();
        assertThat(dto.getInputSchema().path("lines").get(0).path("fields")).hasSize(2);
        assertThat(dto.getInputSchema().path("lines").get(0).path("fields").get(0).path("name").asText()).isEqualTo("R");
        assertThat(dto.getTestPlan()).isNotNull();
        assertThat(dto.getTestPlan().getProblemType()).isEqualTo("generic_fallback_input");
        assertThat(dto.getGeneratorCode()).isNullOrEmpty();
    }
}
