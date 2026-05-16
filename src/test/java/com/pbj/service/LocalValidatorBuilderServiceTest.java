package com.pbj.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalValidatorBuilderServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LocalValidatorBuilderService builder = new LocalValidatorBuilderService();

    @Test
    void generatedValidatorLoopsOverMultipleTestCases() throws Exception {
        String validator = builder.buildFromInputSchema(objectMapper.readTree("""
                {
                  "multiple_test_cases": true,
                  "lines": [
                    {"kind": "scalars", "fields": [{"name": "N", "type": "int", "min": 1, "max": 10}]},
                    {"kind": "array", "name": "a", "type": "int", "length": "N", "min": 1, "max": 100}
                  ]
                }
                """));

        assertThat(validator).contains("T = to_int(tokens[idx], 'T')");
        assertThat(validator).contains("for _case in range(T):");
        assertThat(validator).contains("        vars_ = {}");
        assertThat(validator).contains("        if idx >= len(tokens): die('missing token for N')");
    }
}
