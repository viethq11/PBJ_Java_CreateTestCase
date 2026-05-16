package com.pbj.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbj.dto.AiResponseDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemTestcaseGeneratorServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProblemTaxonomyResolver resolver = new ProblemTaxonomyResolver();
    private final SystemTestcaseGeneratorService service =
            new SystemTestcaseGeneratorService(resolver, new FallbackGeneratorFactory());

    @Test
    void resolvesAlternatingTypedRoadProblemFromMetadata() throws Exception {
        AiResponseDTO dto = alternatingRoadDto();

        ProblemMetadata metadata = resolver.resolve(dto, sourceStatement());

        assertThat(metadata.type()).isEqualTo(ProblemType.GRAPH_ALTERNATING_EDGE_SHORTEST_PATH);
    }

    @Test
    void buildsSystemGeneratorForAlternatingTypedRoadProblem() throws Exception {
        AiResponseDTO dto = alternatingRoadDto();

        String generator = service.buildGenerator(dto, sourceStatement());

        assertThat(generator).contains("struct Edge");
        assertThat(generator).contains("cout << e.u << ' ' << e.v << ' ' << e.w << ' ' << e.type");
        assertThat(generator).contains("anti_greedy_small");
        assertThat(generator).contains("tie_breaking");
        assertThat(generator).contains("stress_performance");
        assertThat(generator).doesNotContain("%%");
    }

    @Test
    void fallsBackToGenericSchemaGeneratorForUnknownTaxonomy() throws Exception {
        AiResponseDTO dto = new AiResponseDTO();
        dto.setInputSchema(objectMapper.readTree("""
                {
                  "multiple_test_cases": false,
                  "lines": [
                    {"kind": "scalars", "fields": [{"name": "N", "type": "int", "min": 1, "max": 100}]},
                    {"kind": "array", "name": "A", "type": "int", "length": "N", "min": 1, "max": 1000}
                  ]
                }
                """));

        String generator = service.buildGenerator(dto, "Generic array task.");

        assertThat(generator).contains("chooseCount");
        assertThat(generator).contains("cout << N");
    }

    private AiResponseDTO alternatingRoadDto() throws Exception {
        AiResponseDTO dto = new AiResponseDTO();
        dto.setInputFormat("Dòng đầu chứa N M. M dòng tiếp theo chứa u v W type.");
        dto.setOutputFormat("In ra thời gian ngắn nhất, hoặc -1 nếu không thể đến đích.");
        dto.setConstraints("2 <= N <= 100000; 1 <= M <= 200000; 1 <= W <= 1000000000; type là 0 hoặc 1.");
        dto.setInputSchema(objectMapper.readTree("""
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
                """));
        AiResponseDTO.TestPlan plan = new AiResponseDTO.TestPlan();
        plan.setProblemType("GRAPH_ALTERNATING_EDGE_SHORTEST_PATH");
        plan.setIntendedSolution("Dijkstra trên trạng thái đỉnh và loại cạnh cuối.");
        dto.setTestPlan(plan);
        return dto;
    }

    private String sourceStatement() {
        return """
                Bản đồ Nam Cường gồm N thành phố và M con đường hai chiều.
                Mỗi con đường có thuộc tính Âm hoặc Dương. Cần tìm thời gian ngắn nhất từ 1 đến N
                sao cho các loại cạnh luân phiên. Mỗi dòng cạnh chứa u, v, W, type.
                """;
    }
}
