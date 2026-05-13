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

    @Test
    void rejectsSpecDriftFromAlternatingTypedRoadProblemToDagArrayProblem() throws Exception {
        AiResponseDTO dto = completeDto("""
                {
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
                }
                """);
        dto.setFormattedDescription("Cho một đồ thị có hướng không chu trình DAG. Mỗi đỉnh có giá trị A[i].");
        dto.setInputFormat("Dòng đầu chứa N M. Dòng thứ hai chứa N số A[i]. M dòng sau chứa u v w.");
        dto.setConstraints("1 <= N, M <= 200000; 1 <= A[i], w <= 1000000000");
        dto.getTestPlan().setProblemType("DAG dynamic programming");
        dto.getTestPlan().setIntendedSolution("DP trên DAG");

        String source = """
                Bản đồ Nam Cường bao gồm N thành phố và M con đường hai chiều kết nối chúng.
                Mỗi con đường mang thuộc tính Âm (0) hoặc Dương (1). Cần đi từ thành phố 1 đến N
                sao cho các thuộc tính đường đi luân phiên Âm - Dương. Mỗi con đường mất thời gian W.
                M dòng tiếp theo, mỗi dòng chứa 4 số nguyên u, v, W, type.
                """;

        assertThatThrownBy(() -> service.validateAgainstSource(source, dto))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different problem")
                .hasMessageContaining("4 values per edge");
    }

    @Test
    void acceptsGroundedTypedRoadSchema() throws Exception {
        AiResponseDTO dto = completeDto("""
                {
                  "multiple_test_cases": false,
                  "lines": [
                    {
                      "kind": "scalars",
                      "fields": [
                        {"name": "N", "type": "int", "min": 2, "max": 100000},
                        {"name": "M", "type": "int", "min": 1, "max": 200000}
                      ]
                    },
                    {
                      "kind": "edges",
                      "length": "M",
                      "directed": false,
                      "self_loop_allowed": false,
                      "multi_edge_allowed": true,
                      "columns": [
                        {"name": "u", "type": "node", "min": 1, "max": "N"},
                        {"name": "v", "type": "node", "min": 1, "max": "N"},
                        {"name": "W", "type": "int", "min": 1, "max": 1000000000},
                        {"name": "type", "type": "int", "min": 0, "max": 1}
                      ]
                    }
                  ]
                }
                """);
        dto.setFormattedDescription("Tìm thời gian ngắn nhất từ thành phố 1 đến N trên các con đường hai chiều, thuộc tính Âm hoặc Dương phải luân phiên.");
        dto.setInputFormat("Dòng đầu chứa N và M. M dòng tiếp theo chứa u, v, W, type.");
        dto.setConstraints("2 <= N <= 100000; 1 <= M <= 200000; 1 <= W <= 1000000000; type là 0 hoặc 1.");
        dto.getTestPlan().setProblemType("shortest path with alternating edge types");
        dto.getTestPlan().setIntendedSolution("Dijkstra trên trạng thái (đỉnh, loại cạnh cuối).");

        String source = """
                Bản đồ Nam Cường bao gồm N thành phố và M con đường hai chiều kết nối chúng.
                Mỗi con đường mang thuộc tính Âm (0) hoặc Dương (1). Cần đi từ thành phố 1 đến N
                sao cho các thuộc tính đường đi luân phiên Âm - Dương. Mỗi con đường mất thời gian W.
                M dòng tiếp theo, mỗi dòng chứa 4 số nguyên u, v, W, type.
                """;

        assertThatCode(() -> service.validateAgainstSource(source, dto)).doesNotThrowAnyException();
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
