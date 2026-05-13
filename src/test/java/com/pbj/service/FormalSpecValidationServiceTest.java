package com.pbj.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbj.dto.AiResponseDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormalSpecValidationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FormalSpecValidationService service = new FormalSpecValidationService();

    @Test
    void acceptsConsistentFormalSpec() throws Exception {
        AiResponseDTO dto = completeDto("""
                {
                  "multiple_test_cases": false,
                  "lines": [
                    {
                      "kind": "scalars",
                      "fields": [
                        {"name": "N", "type": "int", "min": 1, "max": 100000},
                        {"name": "M", "type": "int", "min": 1, "max": 100000}
                      ]
                    },
                    {"kind": "array", "name": "D", "type": "int", "length": "N", "min": 1, "max": 1000000000},
                    {"kind": "array", "name": "P", "type": "int", "length": "M", "min": 1, "max": 1000000000}
                  ]
                }
                """);

        assertThatCode(() -> service.validateForGeneration(dto)).doesNotThrowAnyException();
    }

    @Test
    void rejectsUndefinedLengthReference() throws Exception {
        AiResponseDTO dto = completeDto("""
                {
                  "multiple_test_cases": false,
                  "lines": [
                    {"kind": "scalars", "fields": [{"name": "N", "type": "int", "min": 1, "max": 100000}]},
                    {"kind": "array", "name": "A", "type": "int", "length": "M", "min": 1, "max": 100}
                  ]
                }
                """);

        assertThatThrownBy(() -> service.validateForGeneration(dto))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("undefined scalar 'M'");
    }

    private AiResponseDTO completeDto(String schemaJson) throws Exception {
        AiResponseDTO dto = new AiResponseDTO();
        dto.setInputFormat("Dong 1 chua N M. Dong 2 chua N so. Dong 3 chua M so.");
        dto.setOutputFormat("In ra mot so nguyen.");
        dto.setConstraints("1 <= N, M <= 100000; 1 <= values <= 1000000000");
        dto.setValidatorCode("import sys\\n");
        dto.setGoldenSolution("#include <bits/stdc++.h>\\nint main(){return 0;}");
        JsonNode schema = objectMapper.readTree(schemaJson);
        dto.setInputSchema(schema);

        AiResponseDTO.TestPlan testPlan = new AiResponseDTO.TestPlan();
        testPlan.setProblemType("matching arrays");
        testPlan.setIntendedSolution("sort and two pointers");
        AiResponseDTO.TestFamily family = new AiResponseDTO.TestFamily();
        family.setName("max_constraints");
        family.setDifficulty("stress");
        family.setTarget(List.of("quadratic"));
        family.setConstraints("N and M near max");
        family.setExpected("valid large case");
        family.setReason("kills quadratic solutions");
        testPlan.setTestFamilies(List.of(family));
        dto.setTestPlan(testPlan);
        return dto;
    }
}
