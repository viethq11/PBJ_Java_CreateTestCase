package com.pbj.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbj.dto.AiResponseDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdversarialTestSynthesisServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AdversarialTestSynthesisService service = new AdversarialTestSynthesisService();

    @Test
    void synthesizesMaxConstraintTwoArrayCases() throws Exception {
        AiResponseDTO dto = new AiResponseDTO();
        dto.setInputSchema(objectMapper.readTree("""
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
                """));

        List<String> cases = service.synthesize(dto);

        assertThat(cases).hasSizeGreaterThanOrEqualTo(4);
        assertThat(cases.get(0)).startsWith("100000 100000\n");
        assertThat(cases.get(0).lines().toList().get(1).split(" ")).hasSize(100000);
        assertThat(cases.get(0).lines().toList().get(2).split(" ")).hasSize(100000);
    }

    @Test
    void synthesizesCompactGridRows() throws Exception {
        AiResponseDTO dto = new AiResponseDTO();
        dto.setInputSchema(objectMapper.readTree("""
                {
                  "multiple_test_cases": false,
                  "lines": [
                    {
                      "kind": "scalars",
                      "fields": [
                        {"name": "N", "type": "int", "min": 1, "max": 5},
                        {"name": "M", "type": "int", "min": 1, "max": 4}
                      ]
                    },
                    {"kind": "grid", "rows": "N", "cols": "M", "alphabet": "01"}
                  ]
                }
                """));

        List<String> cases = service.synthesize(dto);
        List<String> lines = cases.get(0).lines().toList();

        assertThat(lines.get(0)).isEqualTo("5 4");
        assertThat(lines.get(1)).hasSize(4);
        assertThat(lines.get(1)).doesNotContain(" ");
        assertThat(lines.get(1)).matches("[01]+");
    }

    @Test
    void honorsRequestedOverflowAndGreedyProfiles() throws Exception {
        AiResponseDTO dto = new AiResponseDTO();
        dto.setInputSchema(objectMapper.readTree("""
                {
                  "multiple_test_cases": false,
                  "lines": [
                    {"kind": "scalars", "fields": [{"name": "N", "type": "int", "min": 1, "max": 6}]},
                    {"kind": "array", "name": "A", "type": "int", "length": "N", "min": 1, "max": 1000000000}
                  ]
                }
                """));

        AiResponseDTO.TestProfile overflow = new AiResponseDTO.TestProfile();
        overflow.setName("overflow_int32");
        AiResponseDTO.TestProfile greedy = new AiResponseDTO.TestProfile();
        greedy.setName("anti_greedy_small");
        dto.setTestProfiles(List.of(overflow, greedy));

        List<String> cases = service.synthesize(dto);

        assertThat(cases).hasSize(2);
        assertThat(cases.get(0)).contains("1000000000");
        assertThat(cases.get(1).lines().toList().get(0)).isEqualTo("6");
        assertThat(cases.get(1).lines().toList().get(1).split(" ")).hasSize(6);
    }

    @Test
    void overflowProfileBiasesTowardRepeatedExtremes() throws Exception {
        AiResponseDTO dto = new AiResponseDTO();
        dto.setInputSchema(objectMapper.readTree("""
                {
                  "multiple_test_cases": false,
                  "lines": [
                    {"kind": "scalars", "fields": [{"name": "N", "type": "int", "min": 3, "max": 3}]},
                    {"kind": "array", "name": "A", "type": "int", "length": "N", "min": 1, "max": 2147483647}
                  ]
                }
                """));

        AiResponseDTO.TestProfile overflow = new AiResponseDTO.TestProfile();
        overflow.setName("overflow_int32");
        dto.setTestProfiles(List.of(overflow));

        List<String> cases = service.synthesize(dto);
        List<String> values = List.of(cases.get(0).lines().toList().get(1).split(" "));

        assertThat(values).containsExactly("2147483647", "2147483647", "2147482623");
    }

    @Test
    void clampsSelectionCountToGeneratedN() throws Exception {
        AiResponseDTO dto = new AiResponseDTO();
        dto.setInputSchema(objectMapper.readTree("""
                {
                  "multiple_test_cases": false,
                  "lines": [
                    {
                      "kind": "scalars",
                      "fields": [
                        {"name": "N", "type": "int", "min": 2, "max": 5},
                        {"name": "C", "type": "int", "min": 2, "max": 100000}
                      ]
                    },
                    {"kind": "array", "name": "X", "type": "int", "length": "N", "min": 0, "max": 1000000000}
                  ]
                }
                """));

        List<String> lines = service.synthesize(dto).get(0).lines().toList();
        String[] header = lines.get(0).split(" ");

        assertThat(Integer.parseInt(header[1])).isLessThanOrEqualTo(Integer.parseInt(header[0]));
    }
}
