package com.pbj.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbj.dto.AiResponseDTO;
import com.pbj.entity.Problem;
import com.pbj.entity.TestCase;
import com.pbj.repository.ProblemRepository;
import com.pbj.repository.TestCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class ProblemService {

    private final ProblemRepository problemRepository;
    private final TestCaseRepository testCaseRepository;
    private final AiIntegrationService aiIntegrationService;
    private final CodeExecutionService codeExecutionService;
    private final JobQueueService jobQueueService;
    private final TestCaseStorageService testCaseStorageService;
    private final ObjectMapper objectMapper;

    // ======================================================================
    // Test-case batch sizes to run through the generator
    // ======================================================================
    private static final String[][] GENERATOR_RUNS = {
        {"small",  "1"},
        {"small",  "2"},
        {"medium", "3"},
        {"medium", "4"},
        {"medium", "5"},
        {"large",  "6"},
        {"large",  "7"},
        {"large",  "8"},
        {"large",  "9"},
        {"large",  "10"},
        {"stress", "11"},
        {"stress", "12"},
        {"stress", "13"},
        {"stress", "14"},
        {"stress", "15"},
        {"stress", "16"},
        {"stress", "17"},
        {"stress", "18"},
        {"stress", "19"},
        {"stress", "20"},
        {"stress", "21"},
        {"stress", "22"},
        {"stress", "23"},
        {"stress", "24"},
        {"stress", "25"},
        {"stress", "26"},
        {"stress", "27"},
        {"stress", "28"},
        {"stress", "29"},
        {"stress", "30"},
    };

    // ======================================================================
    // PUBLIC API — synchronous read-only / lightweight
    // ======================================================================

    @Transactional(readOnly = true)
    public List<Problem> getAllProblems() {
        return problemRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Problem> searchProblems(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return getAllProblems();
        }
        return problemRepository.findByTitleContainingIgnoreCase(keyword.trim());
    }

    @Transactional
    public void deleteProblem(Long problemId) {
        // Removes files from disk AND rows from DB
        testCaseStorageService.deleteAllForProblem(problemId);
        problemRepository.deleteById(problemId);
    }

    @Transactional(readOnly = true)
    public Problem getProblem(Long id) {
        return problemRepository.findById(id).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<TestCase> getTestCases(Long problemId) {
        return testCaseRepository.findByProblemId(problemId);
    }

    public CodeExecutionService.SubmissionResult runCodeForProblem(Long problemId,
                                                                   String sourceCode,
                                                                   String language,
                                                                   CodeExecutionService.RunResult expectedStatus) {
        Problem p = getProblem(problemId);
        if (p == null) return null;

        List<TestCase> testcases = getTestCases(problemId);
        return codeExecutionService.runCode(sourceCode, language, testcases, p.getTimeLimit(), p.getCheckerCode(), expectedStatus);
    }

    public String generateAcceptedCode(Long problemId, String language) {
        Problem problem = getProblem(problemId);
        if (problem == null) throw new IllegalArgumentException("Problem not found");
        return aiIntegrationService.generateAcceptedCode(
                problem.getTitle(), problem.getDescription(),
                problem.getInputFormat(), problem.getOutputFormat(),
                problem.getConstraints(), language);
    }

    public String generateWrongAnswerCode(Long problemId, String language) {
        Problem problem = getProblem(problemId);
        if (problem == null) throw new IllegalArgumentException("Problem not found");
        return aiIntegrationService.generateWrongAnswerCode(
                problem.getTitle(), problem.getDescription(),
                problem.getInputFormat(), problem.getOutputFormat(),
                problem.getConstraints(), language);
    }

    public String generateTimeLimitExceededCode(Long problemId, String language) {
        Problem problem = getProblem(problemId);
        if (problem == null) throw new IllegalArgumentException("Problem not found");
        return aiIntegrationService.generateTimeLimitExceededCode(
                problem.getTitle(), problem.getDescription(),
                problem.getInputFormat(), problem.getOutputFormat(),
                problem.getConstraints(), language);
    }

    // ======================================================================
    // ASYNC: Generate problem + testcases (pushed to background thread pool)
    // ======================================================================

    /**
     * Creates a job ticket immediately, kicks off work asynchronously,
     * and returns the job ID so the frontend can poll /api/job/{id}.
     */
    public String submitGenerateProblem(String title, String description, List<MultipartFile> images) {
        String jobId = jobQueueService.createJob("GENERATE");
        generateProblemAsync(jobId, title, description, images);
        return jobId;
    }

    @Async("judgeTaskExecutor")
    public void generateProblemAsync(String jobId, String title, String description, List<MultipartFile> images) {
        jobQueueService.updateState(jobId, JobQueueService.JobState.RUNNING);
        try {
            Problem p = generateAndSaveProblem(title, description, images);
            jobQueueService.completeJob(jobId, p.getId());
        } catch (Exception e) {
            jobQueueService.failJob(jobId, e.getMessage());
        }
    }

    // ======================================================================
    // ASYNC: Regenerate testcases
    // ======================================================================

    public String submitRegenerateTestCases(Long problemId) {
        String jobId = jobQueueService.createJob("REGENERATE");
        regenerateTestCasesAsync(jobId, problemId);
        return jobId;
    }

    @Async("judgeTaskExecutor")
    public void regenerateTestCasesAsync(String jobId, Long problemId) {
        jobQueueService.updateState(jobId, JobQueueService.JobState.RUNNING);
        try {
            regenerateTestCases(problemId);
            jobQueueService.completeJob(jobId, "success");
        } catch (Exception e) {
            jobQueueService.failJob(jobId, e.getMessage());
        }
    }

    // ======================================================================
    // ASYNC: Run code submission
    // ======================================================================

    public String submitRunCode(Long problemId, String sourceCode, String language,
                                CodeExecutionService.RunResult expectedStatus) {
        String jobId = jobQueueService.createJob("RUN");
        runCodeAsync(jobId, problemId, sourceCode, language, expectedStatus);
        return jobId;
    }

    @Async("judgeTaskExecutor")
    public void runCodeAsync(String jobId, Long problemId, String sourceCode,
                             String language, CodeExecutionService.RunResult expectedStatus) {
        jobQueueService.updateState(jobId, JobQueueService.JobState.RUNNING);
        try {
            CodeExecutionService.SubmissionResult result = runCodeForProblem(problemId, sourceCode, language, expectedStatus);
            if (result == null) {
                jobQueueService.failJob(jobId, "Problem not found");
            } else {
                jobQueueService.completeJob(jobId, result);
            }
        } catch (Exception e) {
            jobQueueService.failJob(jobId, e.getMessage());
        }
    }

    // ======================================================================
    // INTERNAL: Core synchronous generation logic
    // ======================================================================

    @Transactional
    public Problem generateAndSaveProblem(String title, String description, List<MultipartFile> images) {
        List<String> base64Images = encodeImages(images);

        AiResponseDTO dto = aiIntegrationService.generateTestCases(description, base64Images, GENERATOR_RUNS.length);

        Problem p = buildProblem(title, description, dto);
        p = problemRepository.save(p);

        String goldenCode = resolveGoldenSolution(p, dto);

        if (goldenCode != null && !goldenCode.isBlank()) {
            int savedCount = generateTestCasesFromCode(p, dto, goldenCode);
            verifyGenerationResult(p, savedCount, p.getId(), goldenCode);
        } else {
            saveEdgeCases(p, dto.getEdgeCases(), goldenCode);
        }

        return p;
    }

    @Transactional
    public Problem generateAndSaveProblem(String title, String description) {
        return generateAndSaveProblem(title, description, List.of());
    }

    @Transactional
    public void regenerateTestCases(Long problemId) {
        Problem problem = getProblem(problemId);
        if (problem == null) throw new IllegalArgumentException("Problem not found");

        // Delete all disk files + DB rows
        testCaseStorageService.deleteAllForProblem(problemId);

        AiResponseDTO dto = aiIntegrationService.generateTestCases(
                problem.getDescription(), new ArrayList<>(), GENERATOR_RUNS.length, true);

        if (dto.getConstraints() != null && !dto.getConstraints().isBlank()) {
            updateProblemMetadata(problem, dto);
        }

        String goldenCode = resolveGoldenSolution(problem, dto);
        if (goldenCode == null || goldenCode.isBlank()) {
            throw new IllegalStateException(
                    "Cannot regenerate testcases: failed to obtain a valid AC reference solution.");
        }

        int savedCount = generateTestCasesFromCode(problem, dto, goldenCode);
        verifyGenerationResult(problem, savedCount, problemId, goldenCode);
    }

    // ======================================================================
    // PRIVATE: Generator pipeline
    // ======================================================================

    private int generateTestCasesFromCode(Problem problem, AiResponseDTO dto, String goldenCode) {
        String generatorCode     = dto.getGeneratorCode();
        String generatorLanguage = dto.getGeneratorLanguage() != null ? dto.getGeneratorLanguage() : "python";

        Set<String> fingerprints = new HashSet<>();
        AtomicInteger savedCount = new AtomicInteger(0);

        // 1. Manually crafted edge cases first
        savedCount.addAndGet(saveEdgeCases(problem, dto.getEdgeCases(), goldenCode));

        // 2. Generator-based cases
        if (generatorCode != null && !generatorCode.isBlank()) {
            int tcSeq = savedCount.get() + 1;
            for (String[] run : GENERATOR_RUNS) {
                String size = run[0];
                int seed    = Integer.parseInt(run[1]);

                System.out.println("DEBUG: Running generator seed=" + seed + " size=" + size);
                String generatedInput = codeExecutionService.runGenerator(
                        generatorCode, generatorLanguage, seed, size);

                if (generatedInput == null || generatedInput.isBlank()) {
                    System.err.println("DEBUG: Generator produced no output for seed=" + seed + " size=" + size);
                    continue;
                }

                String expectedOutput = codeExecutionService.runGoldenSolution(
                        goldenCode, "cpp", generatedInput, problem.getTimeLimit());

                if (expectedOutput == null || expectedOutput.isBlank()) {
                    System.err.println("DEBUG: Golden solution failed for seed=" + seed);
                    continue;
                }

                if (!isUniqueTestCase(generatedInput, expectedOutput, fingerprints)) continue;

                boolean isSample = size.equals("small") && seed == 1;
                testCaseStorageService.saveTestCase(problem, generatedInput, expectedOutput, isSample, tcSeq++);
                savedCount.incrementAndGet();
                System.out.println("DEBUG: Saved testcase (seed=" + seed + ", size=" + size + ")");
            }
        }

        return savedCount.get();
    }

    private int saveEdgeCases(Problem problem, List<AiResponseDTO.TestCaseDTO> edgeCases, String goldenCode) {
        if (edgeCases == null || edgeCases.isEmpty()) return 0;

        int saved = 0;
        for (AiResponseDTO.TestCaseDTO ec : edgeCases) {
            String input = ec.getInput();
            if (input == null || input.isBlank()) continue;

            String expectedOutput;
            if (goldenCode != null && !goldenCode.isBlank()) {
                expectedOutput = codeExecutionService.runGoldenSolution(
                        goldenCode, "cpp", input, 5000);
                if (expectedOutput == null) {
                    expectedOutput = ec.getExpectedOutput();
                }
            } else {
                expectedOutput = ec.getExpectedOutput();
            }

            if (expectedOutput == null || expectedOutput.isBlank()) continue;

            testCaseStorageService.saveTestCase(
                    problem, input, expectedOutput,
                    Boolean.TRUE.equals(ec.getIsSample()), saved + 1);
            saved++;
        }
        return saved;
    }

    // ======================================================================
    // PRIVATE: Helpers
    // ======================================================================

    private List<String> encodeImages(List<MultipartFile> images) {
        List<String> base64Images = new ArrayList<>();
        if (images != null) {
            for (MultipartFile file : images) {
                if (!file.isEmpty()) {
                    try {
                        base64Images.add(Base64.getEncoder().encodeToString(file.getBytes()));
                    } catch (Exception e) {
                        System.err.println("WARN: Failed to encode image: " + e.getMessage());
                    }
                }
            }
        }
        return base64Images;
    }

    private Problem buildProblem(String title, String description, AiResponseDTO dto) {
        Problem p = new Problem();
        p.setTitle(title);

        if (dto.getFormattedDescription() != null && !dto.getFormattedDescription().isBlank()) {
            p.setDescription(dto.getFormattedDescription());
        } else {
            p.setDescription(description);
        }

        p.setConstraints(dto.getConstraints());
        p.setInputFormat(dto.getInputFormat());
        p.setOutputFormat(dto.getOutputFormat());
        p.setCheckerCode(dto.getCheckerCode());
        p.setValidatorCode(dto.getValidatorCode());
        p.setTestPlan(toJson(dto.getTestPlan()));
        return p;
    }

    private void updateProblemMetadata(Problem problem, AiResponseDTO dto) {
        if (dto.getConstraints()  != null) problem.setConstraints(dto.getConstraints());
        if (dto.getInputFormat()  != null) problem.setInputFormat(dto.getInputFormat());
        if (dto.getOutputFormat() != null) problem.setOutputFormat(dto.getOutputFormat());
        if (dto.getCheckerCode()  != null) problem.setCheckerCode(dto.getCheckerCode());
        if (dto.getValidatorCode() != null) problem.setValidatorCode(dto.getValidatorCode());
        if (dto.getTestPlan() != null) problem.setTestPlan(toJson(dto.getTestPlan()));
        problemRepository.save(problem);
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            System.err.println("WARN: Failed to serialize AI metadata: " + e.getMessage());
            return null;
        }
    }

    private String resolveGoldenSolution(Problem problem, AiResponseDTO dto) {
        if (dto.getGoldenSolution() != null && !dto.getGoldenSolution().isBlank()) {
            System.out.println("INFO: Using golden solution from AI analysis response.");
            return dto.getGoldenSolution();
        }
        System.out.println("INFO: No golden solution in AI response, requesting separately...");
        return generateAcceptedCode(problem.getId(), "cpp");
    }

    private boolean isUniqueTestCase(String input, String output, Set<String> fingerprints) {
        String normalized = input.replaceAll("\\s+", " ").trim()
                + "###" + output.replaceAll("\\s+", " ").trim();
        if (!fingerprints.add(normalized)) {
            System.err.println("DEBUG: Rejected testcase - duplicate.");
            return false;
        }
        return true;
    }

    private void verifyGenerationResult(Problem problem, int savedCount, Long problemId, String goldenCode) {
        if (savedCount == 0) {
            throw new IllegalStateException(
                    "Failed to generate any valid testcases. " +
                    "Generator script may have failed or golden solution compilation failed.");
        }
        System.out.println("INFO: Generated " + savedCount + " testcases for problem " + problemId);

        List<TestCase> finalCases = getTestCases(problemId);
        try {
            validateVerdictSeparation(problem, finalCases, goldenCode);
            System.out.println("SUCCESS: Verdict separation checks passed.");
        } catch (IllegalStateException validationEx) {
            System.err.println("WARNING: Verdict separation check failed: " + validationEx.getMessage());
            testCaseStorageService.deleteAllForProblem(problemId);
            throw validationEx;
        }
    }

    private void validateVerdictSeparation(Problem problem, List<TestCase> testcases, String goldenCode) {
        CodeExecutionService.SubmissionResult acResult = codeExecutionService.runCode(
                goldenCode, "cpp", testcases,
                problem.getTimeLimit(), problem.getCheckerCode(), null);
        if (acResult == null || acResult.status != CodeExecutionService.RunResult.AC) {
            throw new IllegalStateException("AC reference solution did not pass all generated testcases.");
        }

        String wrongAnswerProbe = """
                #include <iostream>
                int main() {
                    std::cout << 0;
                    return 0;
                }
                """;
        CodeExecutionService.SubmissionResult waResult = codeExecutionService.runCode(
                wrongAnswerProbe, "cpp", testcases,
                problem.getTimeLimit(), problem.getCheckerCode(), null);
        if (waResult == null || waResult.status == CodeExecutionService.RunResult.AC) {
            throw new IllegalStateException("Testcases are weak: wrong-answer probe still gets AC.");
        }

        String tleProbe = "int main() { while (true) {} }";
        CodeExecutionService.SubmissionResult tleResult = codeExecutionService.runCode(
                tleProbe, "cpp", testcases,
                problem.getTimeLimit(), problem.getCheckerCode(), null);
        if (tleResult == null || tleResult.status != CodeExecutionService.RunResult.TLE) {
            throw new IllegalStateException("Runtime cannot reliably classify TLE.");
        }

        try {
            String slowButCorrectProbe = generateTimeLimitExceededCode(problem.getId(), "cpp");
            if (slowButCorrectProbe != null && !slowButCorrectProbe.isBlank()) {
                CodeExecutionService.SubmissionResult slowResult = codeExecutionService.runCode(
                        slowButCorrectProbe, "cpp", testcases,
                        problem.getTimeLimit(), problem.getCheckerCode(), null);
                if (slowResult != null && slowResult.status == CodeExecutionService.RunResult.AC) {
                    throw new IllegalStateException(
                            "Testcases are weak: AI-generated slow correct probe still gets AC. " +
                            "Add max-constraint complexity traps.");
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            System.err.println("WARNING: Could not run generated TLE probe: " + e.getMessage());
        }
    }
}
