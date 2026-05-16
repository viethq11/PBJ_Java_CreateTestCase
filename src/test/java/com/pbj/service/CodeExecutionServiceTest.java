package com.pbj.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodeExecutionServiceTest {

    private final CodeExecutionService service = new CodeExecutionService();

    @Test
    void goldenSolutionReturnsStdoutForSuccessfulRun() {
        String code = """
                public class Main {
                    public static void main(String[] args) {
                        System.out.println(42);
                    }
                }
                """;

        assertThat(service.runGoldenSolution(code, "java", "", 1000)).isEqualTo("42");
    }

    @Test
    void goldenSolutionReturnsNullForRuntimeErrorInsteadOfStderr() {
        String code = """
                public class Main {
                    public static void main(String[] args) {
                        throw new RuntimeException("reference failed");
                    }
                }
                """;

        assertThat(service.runGoldenSolution(code, "java", "", 1000)).isNull();
    }

    @Test
    void detailedGoldenResultIncludesCompileFailureMessage() {
        CodeExecutionService.GoldenResult result =
                service.runGoldenSolutionDetailed("int main( {", "cpp", "", 1000);

        assertThat(result.success).isFalse();
        assertThat(result.output).isNull();
        assertThat(result.message).contains("Golden solution compilation failed");
    }
}
