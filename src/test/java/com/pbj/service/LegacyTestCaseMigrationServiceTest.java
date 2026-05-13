package com.pbj.service;

import com.pbj.entity.Problem;
import com.pbj.entity.TestCase;
import com.pbj.repository.TestCaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegacyTestCaseMigrationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void migratesLegacyPayloadsIntoFilesAndClearsDbColumns() throws Exception {
        TestCaseRepository repository = mock(TestCaseRepository.class);
        LegacyTestCaseMigrationService service = new LegacyTestCaseMigrationService(repository);
        ReflectionTestUtils.setField(service, "storageDir", tempDir.toString());

        Problem problem = new Problem();
        problem.setId(12L);

        TestCase testCase = new TestCase();
        testCase.setId(34L);
        testCase.setProblem(problem);
        testCase.setLegacyInput("3\n1 2 3\n");
        testCase.setLegacyOutput("6\n");

        when(repository.findLegacyPayloadBatch(any())).thenReturn(List.of(testCase));

        LegacyTestCaseMigrationService.MigrationReport report = service.migrateLegacyPayloads(100);

        assertThat(report.migrated()).isEqualTo(1);
        assertThat(testCase.getLegacyInput()).isNull();
        assertThat(testCase.getLegacyOutput()).isNull();
        assertThat(testCase.getInputPath()).isNotBlank();
        assertThat(testCase.getOutputPath()).isNotBlank();
        assertThat(Files.readString(Path.of(testCase.getInputPath()))).isEqualTo("3\n1 2 3\n");
        assertThat(Files.readString(Path.of(testCase.getOutputPath()))).isEqualTo("6\n");
    }

    @Test
    void clearsLegacyColumnsWhenPathsAlreadyExist() throws Exception {
        TestCaseRepository repository = mock(TestCaseRepository.class);
        LegacyTestCaseMigrationService service = new LegacyTestCaseMigrationService(repository);
        ReflectionTestUtils.setField(service, "storageDir", tempDir.toString());

        Path problemDir = tempDir.resolve("problem_7");
        Files.createDirectories(problemDir);
        Path inputPath = problemDir.resolve("tc_9.in");
        Path outputPath = problemDir.resolve("tc_9.out");
        Files.writeString(inputPath, "old-in");
        Files.writeString(outputPath, "old-out");

        Problem problem = new Problem();
        problem.setId(7L);

        TestCase testCase = new TestCase();
        testCase.setId(9L);
        testCase.setProblem(problem);
        testCase.setInputPath(inputPath.toString());
        testCase.setOutputPath(outputPath.toString());
        testCase.setLegacyInput("legacy-in");
        testCase.setLegacyOutput("legacy-out");

        when(repository.findLegacyPayloadBatch(any())).thenReturn(List.of(testCase));

        LegacyTestCaseMigrationService.MigrationReport report = service.migrateLegacyPayloads(100);

        assertThat(report.alreadyExternalized()).isEqualTo(1);
        assertThat(testCase.getLegacyInput()).isNull();
        assertThat(testCase.getLegacyOutput()).isNull();
        assertThat(Files.readString(inputPath)).isEqualTo("old-in");
        assertThat(Files.readString(outputPath)).isEqualTo("old-out");
    }
}
