package com.pbj.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbj.dto.SemanticSpecDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemTextStructureCompilerServiceTest {
    private final ProblemTextStructureCompilerService service =
            new ProblemTextStructureCompilerService(new ObjectMapper());

    @Test
    void derivesGenericCommandStreamModelFromProblemText() {
        SemanticSpecDTO spec = new SemanticSpecDTO();
        String text = """
                The first line contains an integer T, the number of test cases.
                For each test case:
                The first line contains two space-separated integers, n and m.
                The next m lines contain operations in one of the two forms:
                1. UPDATE x y z W
                2. QUERY x1 y1 z1 x2 y2 z2
                """;

        service.enrichMissingInputModel(spec, text);

        assertThat(spec.getInputModel().path("type").asText()).isEqualTo("multi_test_command_based");
        assertThat(spec.getInputModel().path("sections").get(0).path("read_variables")).hasSize(2);
        assertThat(spec.getInputModel().path("sections").get(1).path("loop").path("count_variable").asText())
                .isEqualTo("m");
        assertThat(spec.getInputModel().path("sections").get(1).path("loop").path("body").get(0)
                .path("command_variants")).hasSize(2);
    }
}
