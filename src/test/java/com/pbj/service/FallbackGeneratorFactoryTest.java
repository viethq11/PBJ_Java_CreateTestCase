package com.pbj.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbj.dto.AiResponseDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackGeneratorFactoryTest {

    private final FallbackGeneratorFactory factory = new FallbackGeneratorFactory();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsTargetedTwoArrayFallbackWithoutGenericNoise() {
        AiResponseDTO dto = new AiResponseDTO();
        dto.setInputFormat("""
                Dong dau tien chua hai so nguyen N va M.
                Dong thu hai chua N so nguyen D[1], D[2], ..., D[N].
                Dong thu ba chua M so nguyen P[1], P[2], ..., P[M].
                """);
        dto.setConstraints("1 <= N, M <= 100000; 1 <= D[i], P[j] <= 1000000000");

        List<String> candidates = factory.createCandidates(dto);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0)).contains("int limit = 100000");
        assertThat(candidates.get(0)).contains("cout << n << ' ' << m");
        assertThat(candidates.get(0)).contains("for (int i = 0; i < n; i++)");
        assertThat(candidates.get(0)).contains("for (int i = 0; i < m; i++)");
    }

    @Test
    void schemaGridFallbackPrintsCompactCharacterRows() throws Exception {
        AiResponseDTO dto = new AiResponseDTO();
        dto.setInputFormat("Dong dau chua N M. Tiep theo la N dong, moi dong la xau nhi phan do dai M.");
        dto.setConstraints("1 <= N, M <= 1000; moi ky tu la 0 hoac 1");
        dto.setInputSchema(objectMapper.readTree("""
                {
                  "multiple_test_cases": false,
                  "lines": [
                    {
                      "kind": "scalars",
                      "fields": [
                        {"name": "N", "type": "int", "min": 1, "max": 1000},
                        {"name": "M", "type": "int", "min": 1, "max": 1000}
                      ]
                    },
                    {"kind": "grid", "rows": "N", "cols": "M", "alphabet": "01"}
                  ]
                }
                """));

        List<String> candidates = factory.createCandidates(dto);

        assertThat(candidates.get(0)).contains("string alphabet = \"01\"");
        assertThat(candidates.get(0)).contains("cout << alphabet[idx];");
        assertThat(candidates.get(0)).doesNotContain("if (c) cout << ' '");
    }

    @Test
    void schemaFallbackClampsSelectionCountToN() throws Exception {
        AiResponseDTO dto = new AiResponseDTO();
        dto.setInputSchema(objectMapper.readTree("""
                {
                  "multiple_test_cases": false,
                  "lines": [
                    {
                      "kind": "scalars",
                      "fields": [
                        {"name": "N", "type": "int", "min": 2, "max": 100000},
                        {"name": "C", "type": "int", "min": 2, "max": 100000}
                      ]
                    },
                    {"kind": "array", "name": "X", "type": "int", "length": "N", "min": 0, "max": 1000000000}
                  ]
                }
                """));

        List<String> candidates = factory.createCandidates(dto);

        assertThat(candidates.get(0)).contains("long long C = clampValue(");
        assertThat(candidates.get(0)).contains("min(100000LL, N)");
    }

    @Test
    void schemaFallbackEmitsTestCountForMultipleTestCases() throws Exception {
        AiResponseDTO dto = new AiResponseDTO();
        dto.setInputSchema(objectMapper.readTree("""
                {
                  "multiple_test_cases": true,
                  "lines": [
                    {"kind": "scalars", "fields": [{"name": "N", "type": "int", "min": 1, "max": 1000000}]},
                    {"kind": "array", "name": "a", "type": "int", "length": "N", "min": 1, "max": 1000000000}
                  ]
                }
                """));

        List<String> candidates = factory.createCandidates(dto);

        assertThat(candidates.get(0)).contains("cout << T");
        assertThat(candidates.get(0)).contains("for (long long tc = 0; tc < T; tc++)");
        assertThat(candidates.get(0)).contains("vars.clear();");
    }
}
