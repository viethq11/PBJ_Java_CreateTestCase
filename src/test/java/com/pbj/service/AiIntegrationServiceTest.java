package com.pbj.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbj.dto.AiResponseDTO;
import com.pbj.entity.AiCache;
import com.pbj.repository.AiCacheRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiIntegrationServiceTest {

    @Test
    void fallsBackToSourceOnlyGeminiGenerationWhenRepairStillDrifts() {
        AiCacheRepository cacheRepository = mock(AiCacheRepository.class);
        AiJobQueueService queueService = new AiJobQueueService();
        AiResponseDTO drifted = cubeSummationDto();
        AiResponseDTO grounded = connectopolisDto();
        int[] generationCalls = {0};
        int[] repairCalls = {0};
        OllamaAnalysisService ollamaAnalysisService = new OllamaAnalysisService() {
            @Override
            public String analyzeProblem(String problemText) {
                return "{\"problem_type\":\"wrong\"}";
            }
        };
        GeminiTestGenerationService geminiService = new GeminiTestGenerationService() {
            @Override
            public void verifyApiKeysBeforePipeline() {
                // no-op for unit test
            }

            @Override
            public AiResponseDTO generateTestArtifacts(String problemText, String analysisJson, int count) {
                generationCalls[0]++;
                return "{}".equals(analysisJson) ? grounded : drifted;
            }

            @Override
            public AiResponseDTO repairFormalSpec(String problemText, AiResponseDTO brokenDto, String validationError) {
                repairCalls[0]++;
                return drifted;
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode normalizeInputSchema(com.fasterxml.jackson.databind.JsonNode schema) {
                return schema;
            }
        };
        SystemTestcaseGeneratorService testcaseGeneratorService = new SystemTestcaseGeneratorService(null, null) {
            @Override
            public String buildGenerator(AiResponseDTO dto, String sourceStatement) {
                return "generated";
            }
        };

        when(cacheRepository.findByRequestHash(anyString())).thenReturn(Optional.empty());
        when(cacheRepository.save(any(AiCache.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.setField(queueService, "maxConcurrency", 1);
        ReflectionTestUtils.invokeMethod(queueService, "init");

        AiIntegrationService service = new AiIntegrationService(
                cacheRepository,
                queueService,
                ollamaAnalysisService,
                null,
                geminiService,
                new FormalSpecValidationService(),
                testcaseGeneratorService,
                new ObjectMapper()
        );

        String source = """
                Connectopolis
                There are N cities and M undirected roads.
                Each road is described by four integers u, v, W, type.
                Find the shortest path from 1 to N such that road types alternate.
                """;

        AiResponseDTO result = service.generateTestCases(source, List.of(), 20, false);

        assertThat(result.getInputSchema().path("lines").get(1).path("kind").asText()).isEqualTo("edges");
        assertThat(result.getGeneratorCode()).isEqualTo("generated");
        assertThat(generationCalls[0]).isEqualTo(2);
        assertThat(repairCalls[0]).isEqualTo(1);
    }

    @Test
    void recoversOnlyMissingReferenceArtifactsWithoutReplacingFrozenSpec() {
        AiCacheRepository cacheRepository = mock(AiCacheRepository.class);
        AiJobQueueService queueService = new AiJobQueueService();
        GeminiTestGenerationService geminiService = new GeminiTestGenerationService() {
            @Override
            public void verifyApiKeysBeforePipeline() {
                // no-op for unit test
            }

            @Override
            public AiResponseDTO generateReferenceCandidates(String problemText, AiResponseDTO frozenDto) {
                AiResponseDTO recovered = new AiResponseDTO();
                recovered.setGoldenSolution("#include <bits/stdc++.h>\nint main(){return 0;}");
                recovered.setBruteForceSolution("#include <bits/stdc++.h>\nint main(){return 0;}");
                recovered.setBruteForceLanguage("cpp");
                return recovered;
            }
        };

        when(cacheRepository.findByRequestHash(anyString())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(queueService, "maxConcurrency", 1);
        ReflectionTestUtils.invokeMethod(queueService, "init");

        AiIntegrationService service = new AiIntegrationService(
                cacheRepository,
                queueService,
                null,
                null,
                geminiService,
                new FormalSpecValidationService(),
                null,
                new ObjectMapper()
        );

        AiResponseDTO frozen = connectopolisDto();
        frozen.setGoldenSolution("");
        frozen.setBruteForceSolution("");
        String originalInputFormat = frozen.getInputFormat();

        AiResponseDTO recovered = service.recoverReferenceArtifacts("problem", List.of(), frozen);

        assertThat(recovered).isSameAs(frozen);
        assertThat(recovered.getInputFormat()).isEqualTo(originalInputFormat);
        assertThat(recovered.getGoldenSolution()).contains("int main()");
        assertThat(recovered.getBruteForceSolution()).contains("int main()");
        assertThat(recovered.getBruteForceLanguage()).isEqualTo("cpp");
    }

    private static AiResponseDTO connectopolisDto() {
        AiResponseDTO dto = new AiResponseDTO();
        dto.setFormattedDescription("Tìm đường đi ngắn nhất từ thành phố 1 đến thành phố N với loại đường luân phiên.");
        dto.setUnderstanding("Shortest path on alternating road types.");
        dto.setInputFormat("Dòng đầu chứa N và M. M dòng tiếp theo, mỗi dòng chứa u, v, W, type.");
        dto.setOutputFormat("In ra thời gian ngắn nhất.");
        dto.setConstraints("2 <= N <= 100000; 1 <= M <= 200000; 1 <= W <= 1000000000; type la 0 hoac 1.");
        java.util.Map<String, Object> schema = java.util.Map.of(
                "multiple_test_cases", false,
                "lines", List.of(
                        java.util.Map.of(
                                "kind", "scalars",
                                "fields", List.of(
                                        java.util.Map.of("name", "N", "type", "int", "min", 2, "max", 100000),
                                        java.util.Map.of("name", "M", "type", "int", "min", 1, "max", 200000)
                                )
                        ),
                        java.util.Map.of(
                                "kind", "edges",
                                "length", "M",
                                "directed", false,
                                "self_loop_allowed", false,
                                "multi_edge_allowed", true,
                                "columns", List.of(
                                        java.util.Map.of("name", "u", "type", "node", "min", 1, "max", "N"),
                                        java.util.Map.of("name", "v", "type", "node", "min", 1, "max", "N"),
                                        java.util.Map.of("name", "W", "type", "int", "min", 1, "max", 1000000000),
                                        java.util.Map.of("name", "type", "type", "int", "min", 0, "max", 1)
                                )
                        )
                )
        );
        dto.setInputSchema(new ObjectMapper().valueToTree(schema));
        dto.setGoldenSolution("#include <bits/stdc++.h>\nint main(){return 0;}");
        dto.setValidatorCode("");
        dto.setTestPlan(testPlan("GRAPH_ALTERNATING_EDGE_SHORTEST_PATH", "Dijkstra tren trang thai."));
        return dto;
    }

    private static AiResponseDTO cubeSummationDto() {
        AiResponseDTO dto = new AiResponseDTO();
        dto.setFormattedDescription("Xu ly cac lenh UPDATE va QUERY tren mang 3 chieu.");
        dto.setUnderstanding("3D BIT with update/query commands.");
        dto.setInputFormat("Mỗi truy vấn là UPDATE hoặc QUERY.");
        dto.setOutputFormat("In ket qua cho moi QUERY.");
        dto.setConstraints("1 <= N, M <= 1000");
        java.util.Map<String, Object> schema = java.util.Map.of(
                "multiple_test_cases", true,
                "lines", List.of(
                        java.util.Map.of(
                                "kind", "scalars",
                                "fields", List.of(
                                        java.util.Map.of("name", "T", "type", "int", "min", 1, "max", 50)
                                )
                        ),
                        java.util.Map.of(
                                "kind", "queries",
                                "length", "M",
                                "columns", List.of(
                                        java.util.Map.of("name", "x", "type", "int", "min", 1, "max", "N"),
                                        java.util.Map.of("name", "y", "type", "int", "min", 1, "max", "N"),
                                        java.util.Map.of("name", "z", "type", "int", "min", 1, "max", "N"),
                                        java.util.Map.of("name", "w", "type", "int", "min", 1, "max", 1000000000)
                                )
                        )
                )
        );
        dto.setInputSchema(new ObjectMapper().valueToTree(schema));
        dto.setGoldenSolution("#include <bits/stdc++.h>\nint main(){return 0;}");
        dto.setValidatorCode("");
        dto.setTestPlan(testPlan("GENERIC_SCHEMA", "Fenwick 3D."));
        return dto;
    }

    private static AiResponseDTO.TestPlan testPlan(String problemType, String solution) {
        AiResponseDTO.TestPlan plan = new AiResponseDTO.TestPlan();
        plan.setProblemType(problemType);
        plan.setIntendedSolution(solution);
        AiResponseDTO.TestFamily family = new AiResponseDTO.TestFamily();
        family.setName("baseline");
        family.setDifficulty("small");
        family.setTarget(List.of("wrong"));
        family.setConstraints("small valid case");
        family.setExpected("valid");
        family.setReason("baseline coverage");
        plan.setTestFamilies(List.of(family));
        return plan;
    }
}
