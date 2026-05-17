package com.pbj.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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

    @Test
    void goldenSolutionTimesOutEvenWhenProgramNeverReadsLargeInput() {
        String code = """
                public class Main {
                    public static void main(String[] args) throws Exception {
                        Thread.sleep(10_000);
                    }
                }
                """;
        String largeInput = "x".repeat(2_000_000);

        assertTimeoutPreemptively(Duration.ofSeconds(4), () ->
                assertThat(service.runGoldenSolution(code, "java", largeInput, 100)).isNull());
    }

    @Test
    void resolveCommandUsesBundledWorkspaceToolchainWhenPresent(@TempDir Path tempDir) throws Exception {
        Path compiler = tempDir.resolve("tools").resolve("w64extract").resolve("w64devkit").resolve("bin").resolve("g++.exe");
        Files.createDirectories(compiler.getParent());
        Files.writeString(compiler, "stub");

        String originalUserDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", tempDir.toString());
            Method method = CodeExecutionService.class.getDeclaredMethod("resolveCommand", String.class);
            method.setAccessible(true);

            String resolved = (String) method.invoke(service, "g++");

            assertThat(resolved).isEqualTo(compiler.toAbsolutePath().toString());
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }
}
