package com.pbj.service;

import com.pbj.entity.TestCase;
import com.pbj.repository.TestCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LegacyTestCaseMigrationService {

    private final TestCaseRepository testCaseRepository;

    @Value("${testcase.storage-dir:/app/testcase-data}")
    private String storageDir;

    @Transactional
    public MigrationReport migrateLegacyPayloads(int batchSize) {
        int migrated = 0;
        int alreadyExternalized = 0;
        int skipped = 0;

        List<TestCase> batch = testCaseRepository.findLegacyPayloadBatch(PageRequest.of(0, Math.max(1, batchSize)));
        for (TestCase testCase : batch) {
            if (testCase.getProblem() == null || testCase.getProblem().getId() == null || testCase.getId() == null) {
                skipped++;
                continue;
            }

            String legacyInput = testCase.getLegacyInput();
            String legacyOutput = testCase.getLegacyOutput();
            boolean hasLegacyInput = legacyInput != null && !legacyInput.isBlank();
            boolean hasLegacyOutput = legacyOutput != null && !legacyOutput.isBlank();
            if (!hasLegacyInput && !hasLegacyOutput) {
                skipped++;
                continue;
            }

            boolean hasPaths = testCase.getInputPath() != null && !testCase.getInputPath().isBlank()
                    && testCase.getOutputPath() != null && !testCase.getOutputPath().isBlank();
            if (hasPaths) {
                clearLegacyColumns(testCase);
                alreadyExternalized++;
                continue;
            }

            Path problemDir = Paths.get(storageDir).resolve("problem_" + testCase.getProblem().getId());
            Path inPath = problemDir.resolve("tc_" + testCase.getId() + ".in");
            Path outPath = problemDir.resolve("tc_" + testCase.getId() + ".out");

            try {
                Files.createDirectories(problemDir);
                Files.writeString(inPath, legacyInput == null ? "" : legacyInput);
                Files.writeString(outPath, legacyOutput == null ? "" : legacyOutput);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to externalize legacy testcase id=" + testCase.getId(), e);
            }

            testCase.setInputPath(inPath.toAbsolutePath().toString());
            testCase.setOutputPath(outPath.toAbsolutePath().toString());
            clearLegacyColumns(testCase);
            migrated++;
        }

        return new MigrationReport(migrated, alreadyExternalized, skipped, batch.size());
    }

    private void clearLegacyColumns(TestCase testCase) {
        testCase.setLegacyInput(null);
        testCase.setLegacyOutput(null);
    }

    public record MigrationReport(int migrated,
                                  int alreadyExternalized,
                                  int skipped,
                                  int scanned) {}
}
