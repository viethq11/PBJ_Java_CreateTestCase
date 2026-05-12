package com.pbj.service;

import com.pbj.entity.Problem;
import com.pbj.entity.TestCase;
import com.pbj.repository.TestCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Handles persisting TestCase input/output as physical files on disk.
 *
 * Layout:
 *   <storage-dir>/
 *     problem_<id>/
 *       tc_<seq>.in
 *       tc_<seq>.out
 *
 * The DB row for each TestCase only stores the file paths.
 * This prevents Database Bloat when test data is large (N = 10^5).
 */
@Service
@RequiredArgsConstructor
public class TestCaseStorageService {

    @Value("${testcase.storage-dir:/app/testcase-data}")
    private String storageDir;

    private final TestCaseRepository testCaseRepository;

    // ------------------------------------------------------------------
    // WRITE
    // ------------------------------------------------------------------

    /**
     * Saves a testcase's input/output to disk and persists the file paths to DB.
     *
     * @param problem   the owning problem
     * @param input     raw input text
     * @param output    raw expected output text
     * @param isSample  whether this testcase is a public sample
     * @param seqNum    sequence number used in the filename (unique per problem)
     * @return the persisted TestCase entity (with paths set and content cached)
     */
    @Transactional
    public TestCase saveTestCase(Problem problem, String input, String output,
                                 boolean isSample, int seqNum) {
        Path problemDir = getProblemDir(problem.getId());
        try {
            Files.createDirectories(problemDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create testcase directory: " + problemDir, e);
        }

        Path inPath  = problemDir.resolve("tc_" + seqNum + ".in");
        Path outPath = problemDir.resolve("tc_" + seqNum + ".out");

        try {
            Files.writeString(inPath,  input  != null ? input  : "");
            Files.writeString(outPath, output != null ? output : "");
        } catch (IOException e) {
            throw new RuntimeException("Cannot write testcase files: " + e.getMessage(), e);
        }

        TestCase tc = new TestCase();
        tc.setProblem(problem);
        tc.setInputPath(inPath.toAbsolutePath().toString());
        tc.setOutputPath(outPath.toAbsolutePath().toString());
        tc.setIsSample(isSample);
        // Pre-populate transient cache so callers don't need another disk read
        tc.setInputData(input);
        tc.setOutputData(output);

        return testCaseRepository.save(tc);
    }

    // ------------------------------------------------------------------
    // DELETE
    // ------------------------------------------------------------------

    /**
     * Deletes all testcase files for a given problem from disk,
     * then removes DB rows.
     */
    @Transactional
    public void deleteAllForProblem(Long problemId) {
        // Remove DB rows
        testCaseRepository.deleteByProblemId(problemId);

        // Remove files from disk
        Path problemDir = getProblemDir(problemId);
        deleteDirectory(problemDir.toFile());
    }

    // ------------------------------------------------------------------
    // INTERNAL
    // ------------------------------------------------------------------

    private Path getProblemDir(Long problemId) {
        return Paths.get(storageDir).resolve("problem_" + problemId);
    }

    private void deleteDirectory(java.io.File dir) {
        if (!dir.exists()) return;
        if (dir.isDirectory()) {
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File f : files) deleteDirectory(f);
            }
        }
        dir.delete();
    }
}
