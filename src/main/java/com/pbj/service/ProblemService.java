package com.pbj.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbj.dto.AiResponseDTO;
import com.pbj.entity.Problem;
import com.pbj.entity.TestCase;
import com.pbj.repository.ProblemRepository;
import com.pbj.repository.TestCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ProblemService {

    private final ProblemRepository problemRepository;
    private final TestCaseRepository testCaseRepository;
    private final AiIntegrationService aiIntegrationService;
    private final CodeExecutionService codeExecutionService;
    private final JobQueueService jobQueueService;
    private final TestCaseStorageService testCaseStorageService;
    private final FallbackGeneratorFactory fallbackGeneratorFactory;
    private final FormalSpecValidationService formalSpecValidationService;
    private final AdversarialTestSynthesisService adversarialTestSynthesisService;
    private final LocalValidatorBuilderService localValidatorBuilderService;
    @Qualifier("judgeTaskExecutor")
    private final Executor judgeTaskExecutor;
    private final ObjectMapper objectMapper;

    @Value("${ai.debug-dir:/app/ai-debug}")
    private String aiDebugDir;

    // ======================================================================
    // Test-case batch sizes to run through the generator
    // ======================================================================
    private static final String[][] GENERATOR_RUNS = {
        {"edge_boundary", "1"},
        {"edge_boundary", "2"},
        {"edge_boundary", "3"},
        {"random_small", "4"},
        {"random_small", "5"},
        {"random_small", "6"},
        {"random_small", "7"},
        {"random_small", "8"},
        {"anti_greedy_small", "9"},
        {"anti_greedy_small", "10"},
        {"tie_breaking", "11"},
        {"tie_breaking", "12"},
        {"medium", "13"},
        {"medium", "14"},
        {"medium", "15"},
        {"medium", "16"},
        {"random_large", "17"},
        {"random_large", "18"},
        {"random_large", "19"},
        {"adversarial_structure", "20"},
        {"adversarial_structure", "21"},
        {"adversarial_structure", "22"},
        {"overflow_int32", "23"},
        {"overflow_int32", "24"},
        {"overflow_int64_if_relevant", "25"},
        {"overflow_int64_if_relevant", "26"},
        {"stress_performance", "27"},
        {"stress_performance", "28"},
        {"stress_performance", "29"},
        {"stress_performance", "30"},
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

    @Transactional
    public CodeExecutionService.SubmissionResult runCodeForProblem(Long problemId,
                                                                   String sourceCode,
                                                                   String language,
                                                                   CodeExecutionService.RunResult expectedStatus) {
        Problem p = getProblem(problemId);
        if (p == null) return null;

        List<TestCase> testcases = getTestCases(problemId);
        CodeExecutionService.SubmissionResult result =
                codeExecutionService.runCode(sourceCode, language, testcases, p.getTimeLimit(), p.getCheckerCode(), expectedStatus);
        if (result != null
                && result.status == CodeExecutionService.RunResult.AC
                && expectedStatus == CodeExecutionService.RunResult.AC) {
            persistAcceptedSolution(p, sourceCode, language);
        }
        return result;
    }

    // ======================================================================
    // ASYNC: Generate problem + testcases (pushed to background thread pool)
    // ======================================================================

    /**
     * Creates a job ticket immediately, kicks off work asynchronously,
     * and returns the job ID so the frontend can poll /api/job/{id}.
     */
    public String submitGenerateProblem(String title, String description, List<MultipartFile> images) {
        List<String> base64Images = encodeImages(images);
        String jobId = jobQueueService.createJob("GENERATE");
        judgeTaskExecutor.execute(() -> generateProblemAsync(jobId, title, description, base64Images));
        return jobId;
    }

    public void generateProblemAsync(String jobId, String title, String description, List<String> base64Images) {
        jobQueueService.updateState(jobId, JobQueueService.JobState.RUNNING);
        try {
            Problem p = generateAndSaveProblemFromBase64(title, description, base64Images);
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
        judgeTaskExecutor.execute(() -> regenerateTestCasesAsync(jobId, problemId));
        return jobId;
    }

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
        judgeTaskExecutor.execute(() -> runCodeAsync(jobId, problemId, sourceCode, language, expectedStatus));
        return jobId;
    }

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
        return generateAndSaveProblemFromBase64(title, description, base64Images);
    }

    @Transactional
    public Problem generateAndSaveProblemFromBase64(String title, String description, List<String> base64Images) {
        Problem p = null;

        try {
            AiResponseDTO dto = prepareGenerationDto(description, base64Images, false);
            p = saveProblemMetadata(null, title, description, dto);
            completeTestGeneration(p, dto, description, base64Images, false);
            return p;
        } catch (GeneratedTestcaseArtifactException e) {
            aiIntegrationService.evictTestGenerationCache(description, base64Images, GENERATOR_RUNS.length);
            if (p != null && p.getId() != null) {
                testCaseStorageService.deleteAllForProblem(p.getId());
            }
            if (e.likelySchemaMismatch() || e.likelyGoldenReferenceFailure()) {
                try {
                    String reason = e.likelySchemaMismatch() ? "Schema mismatch" : "Golden reference failure";
                    System.err.println("WARN: " + reason + " suspected; retrying generation once with evicted AI artifacts.");
                    Problem retried = retryProblemGenerationWithFreshArtifacts(p, title, description, base64Images);
                    if (retried != null) {
                        return retried;
                    }
                } catch (RuntimeException retryEx) {
                    System.err.println("WARN: Fresh-artifact retry failed: " + retryEx.getMessage());
                }
                String guidance = e.likelySchemaMismatch()
                        ? " Please add or correct the textual Input/Output/Constraints; the OCR/image-derived input_schema appears inconsistent with generated inputs."
                        : " The generated AC reference solution could not compile/run on valid generated inputs even after a fresh retry.";
                throw new IllegalStateException(e.getMessage() + guidance, e);
            }
            throw e;
        } catch (RuntimeException e) {
            aiIntegrationService.evictTestGenerationCache(description, base64Images, GENERATOR_RUNS.length);
            if (p != null && p.getId() != null) {
                testCaseStorageService.deleteAllForProblem(p.getId());
            }
            throw e;
        }
    }

    @Transactional
    public Problem generateAndSaveProblem(String title, String description) {
        return generateAndSaveProblem(title, description, List.of());
    }

    private Problem retryProblemGenerationWithFreshArtifacts(Problem existingProblem, String title,
                                                            String description, List<String> base64Images) {
        AiResponseDTO dto = prepareGenerationDto(description, base64Images, true);
        Problem problem = saveProblemMetadata(existingProblem, title, description, dto);
        completeTestGeneration(problem, dto, description, base64Images, true);
        return problem;
    }

    private AiResponseDTO prepareGenerationDto(String description, List<String> base64Images, boolean bypassCache) {
        AiResponseDTO dto = aiIntegrationService.generateTestCases(
                description, base64Images, GENERATOR_RUNS.length, bypassCache);
        ensureLocalArtifacts(dto);
        dto.setValidatorCode(sanitizeValidatorCode(dto.getValidatorCode()));
        formalSpecValidationService.validateForGeneration(dto);
        formalSpecValidationService.validateAgainstSource(description, dto);
        return dto;
    }

    private Problem saveProblemMetadata(Problem existingProblem, String title, String description, AiResponseDTO dto) {
        Problem problem = existingProblem == null ? buildProblem(title, description, dto) : existingProblem;
        if (existingProblem != null) {
            problem.setTitle(title);
            applyProblemMetadata(problem, dto);
        }
        return problemRepository.save(problem);
    }

    private void completeTestGeneration(Problem problem, AiResponseDTO dto, String description,
                                        List<String> base64Images, boolean requireGoldenCode) {
        String goldenCode = resolveGoldenSolution(problem, dto);
        if (goldenCode == null || goldenCode.isBlank()) {
            if (!requireGoldenCode) {
                saveEdgeCases(problem, dto.getEdgeCases(), goldenCode, new RejectionStats());
                aiIntegrationService.saveValidatedTestGeneration(description, base64Images, GENERATOR_RUNS.length, dto);
                return;
            }
            throw new IllegalStateException(
                    "Cannot retry testcase generation: failed to obtain a valid AC reference solution.");
        }

        GenerationOutcome outcome = generateTestCasesFromCode(problem, dto, goldenCode);
        verifyGenerationResult(problem, outcome, problem.getId(), goldenCode, dto);
        aiIntegrationService.saveValidatedTestGeneration(description, base64Images, GENERATOR_RUNS.length, dto);
    }

    @Transactional
    public void regenerateTestCases(Long problemId) {
        Problem problem = getProblem(problemId);
        if (problem == null) throw new IllegalArgumentException("Problem not found");

        // Delete all disk files + DB rows
        testCaseStorageService.deleteAllForProblem(problemId);

        AiResponseDTO dto = prepareGenerationDto(problem.getDescription(), new ArrayList<>(), false);

        if (dto.getConstraints() != null && !dto.getConstraints().isBlank()) {
            updateProblemMetadata(problem, dto);
        }

        String goldenCode = resolveGoldenSolution(problem, dto);
        if (goldenCode == null || goldenCode.isBlank()) {
            throw new IllegalStateException(
                    "Cannot regenerate testcases: failed to obtain a valid AC reference solution.");
        }

        try {
            GenerationOutcome outcome = generateTestCasesFromCode(problem, dto, goldenCode);
            verifyGenerationResult(problem, outcome, problemId, goldenCode, dto);
            aiIntegrationService.saveValidatedTestGeneration(
                    problem.getDescription(), new ArrayList<>(), GENERATOR_RUNS.length, dto);
        } catch (RuntimeException e) {
            aiIntegrationService.evictTestGenerationCache(problem.getDescription(), new ArrayList<>(), GENERATOR_RUNS.length);
            testCaseStorageService.deleteAllForProblem(problemId);
            throw e;
        }
    }

    // ======================================================================
    // PRIVATE: Generator pipeline
    // ======================================================================

    private GenerationOutcome generateTestCasesFromCode(Problem problem, AiResponseDTO dto, String goldenCode) {
        String generatorCode     = prepareValidGenerator(dto);
        String generatorLanguage = dto.getGeneratorLanguage() != null ? dto.getGeneratorLanguage() : "python";

        Set<String> fingerprints = new HashSet<>();
        AtomicInteger savedCount = new AtomicInteger(0);
        AtomicInteger generatedCount = new AtomicInteger(0);
        RejectionStats rejectionStats = new RejectionStats();
        GenerationQualitySummary quality = new GenerationQualitySummary();
        quality.hasBruteForceArtifact = dto.getBruteForceSolution() != null && !dto.getBruteForceSolution().isBlank();

        // 1. Manually crafted edge cases first
        savedCount.addAndGet(saveEdgeCases(problem, dto.getEdgeCases(), goldenCode, rejectionStats));

        // 2. Generator-based cases
        if (generatorCode != null && !generatorCode.isBlank()) {
            int tcSeq = savedCount.get() + 1;
            for (String[] run : GENERATOR_RUNS) {
                String profile = run[0];
                int seed    = Integer.parseInt(run[1]);

                System.out.println("DEBUG: Running generator seed=" + seed + " profile=" + profile);
                CodeExecutionService.GeneratorResult generatorResult = codeExecutionService.runGeneratorDetailed(
                        generatorCode, generatorLanguage, seed, profile);

                if (!generatorResult.success) {
                    System.err.println("DEBUG: Generator failed for seed=" + seed
                            + " profile=" + profile + ": " + generatorResult.message);
                    rejectionStats.generatorFailed++;
                    continue;
                }

                String generatedInput = generatorResult.output;

                CodeExecutionService.ValidatorResult validatorResult =
                        codeExecutionService.runValidatorDetailed(dto.getValidatorCode(), generatedInput);
                if (!validatorResult.valid) {
                    System.err.println("DEBUG: Validator rejected generated input for seed=" + seed
                            + " profile=" + profile + ": " + validatorResult.message);
                    rejectionStats.recordValidatorReject(validatorResult.message);
                    continue;
                }

                CodeExecutionService.GoldenResult goldenResult = codeExecutionService.runGoldenSolutionDetailed(
                        goldenCode, "cpp", generatedInput, problem.getTimeLimit());

                if (!goldenResult.success) {
                    rejectionStats.recordGoldenFailure(goldenResult.message);
                    System.err.println("DEBUG: Golden solution failed for seed=" + seed
                            + ": " + goldenResult.message);
                    continue;
                }
                String expectedOutput = goldenResult.output;

                if (!isUniqueTestCase(generatedInput, expectedOutput, fingerprints)) {
                    rejectionStats.duplicate++;
                    continue;
                }

                boolean isSample = profile.equals("edge_boundary") && seed == 1;
                testCaseStorageService.saveTestCase(problem, generatedInput, expectedOutput, isSample, tcSeq++);
                savedCount.incrementAndGet();
                generatedCount.incrementAndGet();
                quality.acceptedProfiles.add(profile);
                System.out.println("DEBUG: Saved testcase (seed=" + seed + ", profile=" + profile + ")");
            }
        }

        int adversarialCount = saveAdversarialCases(
                problem, dto, goldenCode, fingerprints, savedCount.get() + 1, quality);
        savedCount.addAndGet(adversarialCount);

        int minedCount = saveProbeKillerCases(
                problem, dto, goldenCode, generatorCode, generatorLanguage, fingerprints, savedCount.get() + 1, quality);
        savedCount.addAndGet(minedCount);

        if (generatorCode != null && !generatorCode.isBlank()
                && generatedCount.get() + adversarialCount + minedCount < 8) {
            if (generatedCount.get() + adversarialCount + minedCount == 0 && rejectionStats.goldenFailed > 0) {
                evictAcceptedCodeCache(problem);
            }
            throw new GeneratedTestcaseArtifactException(
                    "Generated testcase artifact is invalid: only "
                    + (generatedCount.get() + adversarialCount + minedCount)
                    + " generator/adversarial cases were accepted, "
                    + rejectionStats.totalRejected() + " were rejected or timed out. "
                    + rejectionStats.summary() + " "
                    + (rejectionStats.likelyGoldenReferenceFailure()
                    ? "The generated AC reference solution failed or timed out on all generated inputs and its cache has been evicted. "
                    : "")
                    + "The cached Gemini generator/validator has been evicted; please try again.",
                    rejectionStats.likelySchemaMismatch(),
                    rejectionStats.likelyGoldenReferenceFailure());
        }

        return new GenerationOutcome(savedCount.get(), quality);
    }

    private int saveProbeKillerCases(Problem problem, AiResponseDTO dto, String goldenCode,
                                     String generatorCode, String generatorLanguage,
                                     Set<String> fingerprints, int startSeq,
                                     GenerationQualitySummary quality) {
        if (generatorCode == null || generatorCode.isBlank()) return 0;

        List<AiResponseDTO.ExecutableWrongSolution> probes = executableWrongSolutions(dto);
        if (probes.isEmpty()) return 0;

        String bruteForceCode = dto.getBruteForceSolution();
        String bruteForceLanguage = normalizeProbeLanguage(dto.getBruteForceLanguage());
        boolean hasBruteForce = bruteForceCode != null && !bruteForceCode.isBlank();
        int bruteForceTimeLimit = Math.max(3000, Math.min(10_000, problem.getTimeLimit() * 3));

        int saved = 0;
        for (Map.Entry<String, Integer> run : buildProbeMiningProfiles(dto).entrySet()) {
            String profile = run.getKey();
            int attempts = run.getValue();

            for (int i = 0; i < attempts && saved < 6; i++) {
                int seed = 200 + saved * 50 + i + Math.abs(profile.hashCode() % 31);
                CodeExecutionService.GeneratorResult generatorResult = codeExecutionService.runGeneratorDetailed(
                        generatorCode, generatorLanguage, seed, profile);
                if (!generatorResult.success) continue;

                String input = generatorResult.output;
                CodeExecutionService.ValidatorResult validatorResult =
                        codeExecutionService.runValidatorDetailed(dto.getValidatorCode(), input);
                if (!validatorResult.valid) continue;

                CodeExecutionService.GoldenResult goldenResult = codeExecutionService.runGoldenSolutionDetailed(
                        goldenCode, "cpp", input, problem.getTimeLimit());
                if (!goldenResult.success) continue;
                String goldenOutput = goldenResult.output;

                if (hasBruteForce && isBruteForceFriendlyProfile(profile)) {
                    String bruteForceOutput = codeExecutionService.runGoldenSolution(
                            bruteForceCode, bruteForceLanguage, input, bruteForceTimeLimit);
                    if (bruteForceOutput == null || bruteForceOutput.isBlank()) {
                        continue;
                    }
                    if (!normalizedOutputEquals(goldenOutput, bruteForceOutput)) {
                        throw new IllegalStateException(
                                "Golden solution disagrees with brute-force checker on profile=" + profile
                                        + " seed=" + seed + ". This indicates the reference solution or spec is inconsistent.");
                    }
                    quality.bruteForceVerifiedCases++;
                }

                Set<String> killed = killedProbeNames(problem, probes, input, goldenOutput);
                if (killed.isEmpty()) continue;
                if (!isUniqueTestCase(input, goldenOutput, fingerprints)) continue;

                testCaseStorageService.saveTestCase(problem, input, goldenOutput, false, startSeq + saved);
                saved++;
                quality.minedProbeKillerCases++;
                quality.acceptedProfiles.add(profile);
                quality.killedProbeNames.addAll(killed);
                System.out.println("INFO: Saved probe-killer testcase profile=" + profile
                        + " seed=" + seed + " kills=" + String.join(", ", killed));
            }
        }

        if (saved > 0) {
            System.out.println("INFO: Added " + saved + " mined probe-killer testcases.");
        }
        return saved;
    }

    private int saveAdversarialCases(Problem problem, AiResponseDTO dto, String goldenCode,
                                     Set<String> fingerprints, int startSeq,
                                     GenerationQualitySummary quality) {
        List<String> candidates = adversarialTestSynthesisService.synthesize(dto);
        if (candidates.isEmpty()) return 0;

        int saved = 0;
        for (String input : candidates) {
            if (input == null || input.isBlank()) continue;

            CodeExecutionService.ValidatorResult validatorResult =
                    codeExecutionService.runValidatorDetailed(dto.getValidatorCode(), input);
            if (!validatorResult.valid) {
                System.err.println("DEBUG: Validator rejected adversarial testcase: " + validatorResult.message);
                continue;
            }

            CodeExecutionService.GoldenResult goldenResult = codeExecutionService.runGoldenSolutionDetailed(
                    goldenCode, "cpp", input, problem.getTimeLimit());
            if (!goldenResult.success) {
                System.err.println("DEBUG: Golden solution failed for adversarial testcase: "
                        + goldenResult.message);
                continue;
            }
            String expectedOutput = goldenResult.output;

            if (!isUniqueTestCase(input, expectedOutput, fingerprints)) {
                continue;
            }

            testCaseStorageService.saveTestCase(problem, input, expectedOutput, false, startSeq + saved);
            saved++;
            quality.deterministicAdversarialCases++;
            System.out.println("DEBUG: Saved adversarial testcase #" + saved);
        }

        if (saved > 0) {
            System.out.println("INFO: Added " + saved + " deterministic adversarial testcases.");
        }
        return saved;
    }

    private String prepareValidGenerator(AiResponseDTO dto) {
        String generatorCode = dto.getGeneratorCode();
        if (generatorCode == null || generatorCode.isBlank()) return generatorCode;

        String language = dto.getGeneratorLanguage() != null ? dto.getGeneratorLanguage() : "python";
        String[] probeSizes = buildGeneratorProbeProfiles();
        String lastError = "";
        logGeneratorDebug("initial", generatorCode);

        ProbeResult initialProbe = probeGenerator(dto, generatorCode, language, probeSizes);
        if (initialProbe.valid) {
            dto.setGeneratorCode(generatorCode);
            dto.setGeneratorLanguage(language);
            return generatorCode;
        }
        lastError = initialProbe.message;
        System.err.println("WARN: System generator failed probe validation: " + lastError);

        System.err.println("WARN: Trying backend fallback generator candidates.");
        List<String> fallbackCandidates = fallbackGeneratorFactory.createCandidates(dto);
        for (int i = 0; i < fallbackCandidates.size(); i++) {
            String candidate = fallbackCandidates.get(i);
            logGeneratorDebug("fallback_" + (i + 1), candidate);
            ProbeResult probe = probeGenerator(dto, candidate, "cpp", probeSizes);
            if (probe.valid) {
                System.out.println("INFO: Fallback generator candidate " + (i + 1) + " passed validator probes.");
                dto.setGeneratorCode(candidate);
                dto.setGeneratorLanguage("cpp");
                return candidate;
            }
            System.err.println("WARN: Fallback generator candidate " + (i + 1) + " rejected: " + probe.message);
            lastError = probe.message;
        }

        throw new IllegalStateException(
                "Generated testcase artifact is invalid after generator repair and fallback attempts. Last error: " + lastError);
    }

    private String[] buildGeneratorProbeProfiles() {
        LinkedHashSet<String> profiles = new LinkedHashSet<>();
        for (String[] run : GENERATOR_RUNS) {
            if (run != null && run.length > 0 && run[0] != null && !run[0].isBlank()) {
                profiles.add(run[0]);
            }
        }
        return profiles.toArray(String[]::new);
    }

    private ProbeResult probeGenerator(AiResponseDTO dto, String generatorCode, String language, String[] probeSizes) {
        for (int i = 0; i < probeSizes.length; i++) {
            int seed = 101 + i;
            String size = probeSizes[i];
            CodeExecutionService.GeneratorResult generatorResult =
                    codeExecutionService.runGeneratorDetailed(generatorCode, language, seed, size);

            if (!generatorResult.success) {
                return new ProbeResult(false, generatorResult.message);
            }

            String generatedInput = generatorResult.output;
            CodeExecutionService.ValidatorResult validation =
                    codeExecutionService.runValidatorDetailed(dto.getValidatorCode(), generatedInput);
            if (!validation.valid) {
                return new ProbeResult(false, validation.message + "\nGenerated input:\n" + generatedInput);
            }
        }
        return new ProbeResult(true, "OK");
    }

    private record ProbeResult(boolean valid, String message) {}

    private void logGeneratorDebug(String label, String code) {
        try {
            Path dir = Paths.get(aiDebugDir);
            Files.createDirectories(dir);
            String safeLabel = label == null ? "generator" : label.replaceAll("[^a-zA-Z0-9_-]", "_");
            Path file = dir.resolve("generator_" + safeLabel + "_" + UUID.randomUUID() + ".cpp");
            Files.writeString(file, code == null ? "" : code);
            System.out.println("INFO: Generator debug code saved: " + file.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("WARN: Failed to write generator debug file: " + e.getMessage());
        }
    }

    private int saveEdgeCases(Problem problem, List<AiResponseDTO.TestCaseDTO> edgeCases,
                              String goldenCode, RejectionStats rejectionStats) {
        if (edgeCases == null || edgeCases.isEmpty()) return 0;

        int saved = 0;
        for (AiResponseDTO.TestCaseDTO ec : edgeCases) {
            String input = ec.getInput();
            if (input == null || input.isBlank()) continue;

            if (!codeExecutionService.runValidator(problem.getValidatorCode(), input)) {
                System.err.println("DEBUG: Validator rejected manual edge case.");
                if (rejectionStats != null) {
                    rejectionStats.validatorRejected++;
                }
                continue;
            }

            String expectedOutput;
            if (goldenCode != null && !goldenCode.isBlank()) {
                CodeExecutionService.GoldenResult goldenResult = codeExecutionService.runGoldenSolutionDetailed(
                        goldenCode, "cpp", input, 5000);
                expectedOutput = goldenResult.success ? goldenResult.output : null;
                if (expectedOutput == null) {
                    if (rejectionStats != null) {
                        rejectionStats.recordGoldenFailure(goldenResult.message);
                    }
                    expectedOutput = ec.getExpectedOutput();
                }
            } else {
                expectedOutput = ec.getExpectedOutput();
            }

            if (expectedOutput == null || expectedOutput.isBlank()) {
                if (rejectionStats != null) {
                    rejectionStats.recordGoldenFailure("Manual edge case has no expected output.");
                }
                continue;
            }

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
                        throw new IllegalStateException("Failed to read uploaded image before background processing: "
                                + e.getMessage(), e);
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
        p.setValidatorCode(sanitizeValidatorCode(dto.getValidatorCode()));
        p.setTestPlan(toJson(dto.getTestPlan()));
        return p;
    }

    private void updateProblemMetadata(Problem problem, AiResponseDTO dto) {
        applyProblemMetadata(problem, dto);
        problemRepository.save(problem);
    }

    private void applyProblemMetadata(Problem problem, AiResponseDTO dto) {
        if (dto.getConstraints()  != null) problem.setConstraints(dto.getConstraints());
        if (dto.getInputFormat()  != null) problem.setInputFormat(dto.getInputFormat());
        if (dto.getOutputFormat() != null) problem.setOutputFormat(dto.getOutputFormat());
        if (dto.getCheckerCode()  != null) problem.setCheckerCode(dto.getCheckerCode());
        if (dto.getValidatorCode() != null) problem.setValidatorCode(sanitizeValidatorCode(dto.getValidatorCode()));
        if (dto.getTestPlan() != null) problem.setTestPlan(toJson(dto.getTestPlan()));
    }

    private String sanitizeValidatorCode(String validatorCode) {
        if (validatorCode == null || validatorCode.isBlank()) return validatorCode;

        String fixed = validatorCode.replace("\r\n", "\n");
        boolean changed = false;

        String replacedWhitespace = fixed.replace(".whitespace()", ".strip()");
        if (!replacedWhitespace.equals(fixed)) {
            fixed = replacedWhitespace;
            changed = true;
        }

        Pattern typoPattern = Pattern.compile("\\.whitespace\\s*\\(");
        if (typoPattern.matcher(fixed).find()) {
            fixed = typoPattern.matcher(fixed).replaceAll(".strip(");
            changed = true;
        }

        if (changed) {
            System.err.println("WARN: Auto-repaired validator code typo(s) before testcase generation.");
        }
        return fixed;
    }

    private void ensureLocalArtifacts(AiResponseDTO dto) {
        if (dto == null) return;
        if (dto.getValidatorCode() == null || dto.getValidatorCode().isBlank()) {
            dto.setValidatorCode(localValidatorBuilderService.buildFromInputSchema(dto.getInputSchema()));
            System.out.println("INFO: Built validator_code locally from input_schema.");
        }
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
            if (!looksLikeCxxProgram(dto.getGoldenSolution())) {
                System.err.println("WARN: Ignoring non-compilable-looking AI golden_solution artifact.");
            } else {
                System.out.println("INFO: Using golden solution from AI analysis response.");
                return dto.getGoldenSolution();
            }
        }
        if (problem.getAcceptedSolutionCode() != null && !problem.getAcceptedSolutionCode().isBlank()) {
            System.out.println("INFO: Using stored AC reference solution from DB.");
            return problem.getAcceptedSolutionCode();
        }
        System.out.println("INFO: No golden solution in AI artifact; standalone AC code generation is disabled.");
        return null;
    }

    private boolean looksLikeCxxProgram(String code) {
        if (code == null || code.isBlank()) return false;
        String lower = code.toLowerCase(Locale.ROOT);
        if (lower.contains("internal reference solution") || lower.contains("c++17 internal")) {
            return false;
        }
        return Pattern.compile("\\bint\\s+main\\s*\\(").matcher(code).find()
                || Pattern.compile("\\bauto\\s+main\\s*\\(").matcher(code).find();
    }

    private void persistAcceptedSolution(Problem problem, String sourceCode, String language) {
        if (problem == null || problem.getId() == null || sourceCode == null || sourceCode.isBlank()) return;
        problem.setAcceptedSolutionCode(sourceCode);
        problem.setAcceptedSolutionLanguage(language == null || language.isBlank() ? "cpp" : language);
        problemRepository.save(problem);
        System.out.println("INFO: Stored AC reference solution for problem " + problem.getId() + ".");
    }

    private void evictAcceptedCodeCache(Problem problem) {
        if (problem == null) return;
        problem.setAcceptedSolutionCode(null);
        problem.setAcceptedSolutionLanguage(null);
        problemRepository.save(problem);
        System.err.println("WARN: Cleared stored AC reference solution because it failed every generated testcase.");
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

    private void verifyGenerationResult(Problem problem, GenerationOutcome outcome, Long problemId,
                                        String goldenCode, AiResponseDTO dto) {
        if (outcome.savedCount == 0) {
            throw new IllegalStateException(
                    "Failed to generate any valid testcases. " +
                    "Generator script may have failed or golden solution compilation failed.");
        }
        System.out.println("INFO: Generated " + outcome.savedCount + " testcases for problem " + problemId);

        List<TestCase> finalCases = getTestCases(problemId);
        try {
            validateVerdictSeparation(problem, finalCases, goldenCode);
            Set<String> killedBySuite = validateWrongSolutionCoverage(problem, finalCases, dto);
            validateCoverageGates(dto, outcome.quality.withKilledBySuite(killedBySuite));
            System.out.println("SUCCESS: Validation gates passed.");
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
        persistAcceptedSolution(problem, goldenCode, "cpp");

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

    }

    private Set<String> validateWrongSolutionCoverage(Problem problem, List<TestCase> testcases, AiResponseDTO dto) {
        List<AiResponseDTO.ExecutableWrongSolution> probes = executableWrongSolutions(dto);
        if (probes.isEmpty()) return Set.of();

        Set<String> killed = new LinkedHashSet<>();
        for (AiResponseDTO.ExecutableWrongSolution probe : probes) {
            String language = normalizeProbeLanguage(probe.getLanguage());
            CodeExecutionService.SubmissionResult result = codeExecutionService.runCode(
                    probe.getCode(), language, testcases, problem.getTimeLimit(), problem.getCheckerCode(), null);
            if (result != null && result.status != CodeExecutionService.RunResult.AC) {
                killed.add(probe.getName() == null || probe.getName().isBlank() ? "unnamed_probe" : probe.getName());
            }
        }

        if (killed.isEmpty()) {
            throw new IllegalStateException(
                    "Testcases are weak: none of the executable wrong-solution probes failed. " +
                    "Need stronger overflow/greedy/boundary coverage.");
        }

        System.out.println("INFO: Wrong-solution coverage achieved against probes: " + String.join(", ", killed));
        return killed;
    }

    void validateCoverageGates(AiResponseDTO dto, GenerationQualitySummary quality) {
        CoverageGateReport report = buildCoverageGateReport(dto, quality);
        System.out.println("INFO: Test suite coverage gates=" + report.signals);

        if (!report.hasBoundaryCoverage) {
            throw new IllegalStateException("Weak tests: missing boundary-oriented coverage.");
        }
        if (!report.hasSmallCoverage) {
            throw new IllegalStateException("Weak tests: missing small/exhaustive profile coverage.");
        }
        if (!report.hasLargeOrStressCoverage) {
            if (report.hasMediumCoverage && report.hasAdversarialCoverage) {
                System.err.println(
                        "WARNING: Test suite has no accepted large/stress testcase. " +
                        "Keeping it because medium and adversarial coverage passed; " +
                        "large/stress candidates may have been skipped when the internal reference oracle timed out.");
            } else {
                throw new IllegalStateException("Weak tests: missing large/stress profile coverage.");
            }
        }
        if (!report.hasAdversarialCoverage) {
            throw new IllegalStateException("Weak tests: missing adversarial or bug-oriented profile coverage.");
        }
        if (report.hasBruteForceArtifact && !report.hasBruteForceVerification) {
            throw new IllegalStateException("Weak tests: brute-force artifact exists but no small-case cross-check succeeded.");
        }
        if (report.hasOverflowRisk && report.hasOverflowProbe && !report.killsOverflowProbe) {
            throw new IllegalStateException("Weak tests: overflow probe survived; need stronger numeric-extreme cases.");
        }
        if (report.hasOverflowRisk && !report.hasOverflowProbe && !report.hasOverflowProfileCoverage) {
            throw new IllegalStateException("Weak tests: overflow risk detected but there is no overflow-focused coverage.");
        }
        if (report.hasGreedyRisk && report.hasGreedyProbe && !report.killsGreedyProbe) {
            throw new IllegalStateException("Weak tests: greedy probe survived; need stronger anti-greedy/tie-breaking cases.");
        }
        if (report.hasGreedyRisk && !report.hasGreedyProbe && !report.hasGreedyProfileCoverage) {
            throw new IllegalStateException("Weak tests: greedy risk detected but there is no anti-greedy/tie-breaking coverage.");
        }
    }

    CoverageGateReport buildCoverageGateReport(AiResponseDTO dto, GenerationQualitySummary quality) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        boolean hasOverflowRisk = hasBugClass(dto, "overflow") || hasProbeType(dto, "overflow");
        boolean hasGreedyRisk = hasBugClass(dto, "greedy") || hasProbeType(dto, "greedy");

        boolean hasBoundaryCoverage = containsProfileToken(quality.acceptedProfiles, "boundary", "edge", "tie");
        if (hasBoundaryCoverage) {
            signals.add("boundary");
        }

        boolean hasSmallCoverage = containsProfileToken(quality.acceptedProfiles, "small", "exhaustive")
                || quality.bruteForceVerifiedCases > 0;
        if (hasSmallCoverage) {
            signals.add("small_or_exhaustive");
        }

        boolean hasLargeOrStressCoverage = containsProfileToken(quality.acceptedProfiles, "large", "stress", "max");
        if (hasLargeOrStressCoverage) {
            signals.add("large_or_stress");
        }

        boolean hasMediumCoverage = containsProfileToken(quality.acceptedProfiles, "medium");
        if (hasMediumCoverage) {
            signals.add("medium");
        }

        boolean hasAdversarialCoverage = containsProfileToken(
                quality.acceptedProfiles, "adversarial", "greedy", "tie", "overflow", "stress")
                || quality.minedProbeKillerCases > 0
                || quality.deterministicAdversarialCases > 0;
        if (hasAdversarialCoverage) {
            signals.add("bug_oriented_adversarial");
        }

        boolean hasBruteForceVerification = quality.bruteForceVerifiedCases > 0;
        if (hasBruteForceVerification) {
            signals.add("bruteforce_verified");
        }

        boolean hasOverflowProfileCoverage = containsProfileToken(
                quality.acceptedProfiles, "overflow", "int32", "int64");
        if (hasOverflowRisk && hasOverflowProfileCoverage) {
            signals.add("overflow_profile");
        }

        boolean killsOverflowProbe = killsProbeType(dto, quality.killedBySuite, "overflow");
        if (killsOverflowProbe) {
            signals.add("kills_overflow_probe");
        }

        boolean hasGreedyProfileCoverage = containsProfileToken(
                quality.acceptedProfiles, "greedy", "tie");
        if (hasGreedyRisk && hasGreedyProfileCoverage) {
            signals.add("greedy_profile");
        }

        boolean killsGreedyProbe = killsProbeType(dto, quality.killedBySuite, "greedy");
        if (killsGreedyProbe) {
            signals.add("kills_greedy_probe");
        }

        boolean killsBoundaryProbe = killsProbeType(dto, quality.killedBySuite, "boundary", "off_by_one");
        if (killsBoundaryProbe) {
            signals.add("kills_boundary_probe");
        }

        if (containsProfileToken(quality.acceptedProfiles, "stress", "large", "overflow")) {
            signals.add("max_or_stress");
        }

        if (quality.minedProbeKillerCases > 0) {
            signals.add("mined_probe_killers");
        }

        return new CoverageGateReport(
                signals,
                quality.hasBruteForceArtifact,
                hasBruteForceVerification,
                hasBoundaryCoverage,
                hasSmallCoverage,
                hasMediumCoverage,
                hasLargeOrStressCoverage,
                hasAdversarialCoverage,
                hasOverflowRisk,
                hasProbeType(dto, "overflow"),
                hasOverflowProfileCoverage,
                killsOverflowProbe,
                hasGreedyRisk,
                hasProbeType(dto, "greedy"),
                hasGreedyProfileCoverage,
                killsGreedyProbe
        );
    }

    private Map<String, Integer> buildProbeMiningProfiles(AiResponseDTO dto) {
        Map<String, Integer> profiles = new LinkedHashMap<>();
        boolean overflowRisk = hasBugClass(dto, "overflow") || hasProbeType(dto, "overflow");
        boolean greedyRisk = hasBugClass(dto, "greedy") || hasProbeType(dto, "greedy");

        if (dto != null && dto.getTestProfiles() != null) {
            for (AiResponseDTO.TestProfile profile : dto.getTestProfiles()) {
                if (profile == null || profile.getName() == null || profile.getName().isBlank()) continue;
                String name = normalizeGeneratorProfileName(profile.getName());
                String lower = name.toLowerCase(Locale.ROOT);
                if (!isMiningProfile(dto, lower)) continue;
                int attempts = Math.max(8, Math.min(30, (profile.getSeedCount() == null ? 2 : profile.getSeedCount()) * 6));
                profiles.putIfAbsent(name, attempts);
            }
        }

        for (AiResponseDTO.ExecutableWrongSolution probe : executableWrongSolutions(dto)) {
            if (probe.getKilledByProfiles() == null) continue;
            for (String profile : probe.getKilledByProfiles()) {
                if (profile == null || profile.isBlank()) continue;
                String normalized = normalizeGeneratorProfileName(profile);
                profiles.putIfAbsent(normalized, defaultAttemptsForProfile(normalized));
            }
        }

        if (overflowRisk) {
            profiles.putIfAbsent("overflow_int32", 12);
            profiles.putIfAbsent("overflow_int64_if_relevant", 10);
            profiles.putIfAbsent("random_large", 10);
            profiles.putIfAbsent("stress_performance", 12);
        }
        if (greedyRisk) {
            profiles.putIfAbsent("anti_greedy_small", 14);
            profiles.putIfAbsent("tie_breaking", 14);
        }

        if (profiles.isEmpty()) {
            profiles.put("random_small", 12);
            profiles.put("anti_greedy_small", 14);
            profiles.put("tie_breaking", 12);
            profiles.put("edge_boundary", 8);
            if (overflowRisk) {
                profiles.put("overflow_int32", 10);
                profiles.put("overflow_int64_if_relevant", 8);
                profiles.put("stress_performance", 10);
            }
        }

        return profiles;
    }

    private boolean isMiningProfile(AiResponseDTO dto, String lower) {
        if (lower.contains("overflow")) return hasBugClass(dto, "overflow") || hasProbeType(dto, "overflow");
        return lower.contains("small")
                || lower.contains("tie")
                || lower.contains("boundary")
                || lower.contains("greedy");
    }

    private int defaultAttemptsForProfile(String profile) {
        String lower = profile == null ? "" : profile.toLowerCase(Locale.ROOT);
        if (lower.contains("overflow")) return 10;
        if (lower.contains("greedy") || lower.contains("tie")) return 14;
        if (lower.contains("boundary")) return 8;
        return 12;
    }

    private String normalizeGeneratorProfileName(String profile) {
        if (profile == null || profile.isBlank()) return "random_small";
        String normalized = profile.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (normalized) {
            case "sample", "boundary_min", "boundary_max" -> "edge_boundary";
            case "small_exhaustive", "random_small" -> "random_small";
            case "random_medium" -> "medium";
            case "random_large" -> "random_large";
            case "overflow_int32" -> "overflow_int32";
            case "overflow_int64", "overflow_int64_if_relevant" -> "overflow_int64_if_relevant";
            case "duplicate_values" -> "tie_breaking";
            case "tie_breaking" -> "tie_breaking";
            case "adversarial_greedy" -> "anti_greedy_small";
            case "adversarial_sorting", "adversarial_graph_structure", "adversarial_structure" -> "adversarial_structure";
            case "stress_performance" -> "stress_performance";
            default -> normalized;
        };
    }

    private boolean isBruteForceFriendlyProfile(String profile) {
        String lower = profile == null ? "" : profile.toLowerCase(Locale.ROOT);
        return lower.contains("small") || lower.contains("tie") || lower.contains("boundary");
    }

    private Set<String> killedProbeNames(Problem problem, List<AiResponseDTO.ExecutableWrongSolution> probes,
                                         String input, String expectedOutput) {
        Set<String> killed = new LinkedHashSet<>();
        TestCase testcase = new TestCase();
        testcase.setInputData(input);
        testcase.setOutputData(expectedOutput);
        List<TestCase> singleton = List.of(testcase);

        for (AiResponseDTO.ExecutableWrongSolution probe : probes) {
            CodeExecutionService.SubmissionResult result = codeExecutionService.runCode(
                    probe.getCode(),
                    normalizeProbeLanguage(probe.getLanguage()),
                    singleton,
                    problem.getTimeLimit(),
                    problem.getCheckerCode(),
                    null);
            if (result != null && result.status != CodeExecutionService.RunResult.AC) {
                killed.add(probe.getName() == null || probe.getName().isBlank() ? "unnamed_probe" : probe.getName());
            }
        }
        return killed;
    }

    private List<AiResponseDTO.ExecutableWrongSolution> executableWrongSolutions(AiResponseDTO dto) {
        if (dto == null || dto.getWrongSolutions() == null) return List.of();

        List<AiResponseDTO.ExecutableWrongSolution> probes = new ArrayList<>();
        for (AiResponseDTO.ExecutableWrongSolution wrong : dto.getWrongSolutions()) {
            if (wrong == null || wrong.getCode() == null || wrong.getCode().isBlank()) continue;
            if (Boolean.FALSE.equals(wrong.getExpectedToFail())) continue;
            probes.add(wrong);
        }
        return probes;
    }

    private boolean normalizedOutputEquals(String left, String right) {
        if (left == null || right == null) return left == right;
        return left.trim().replaceAll("\\s+", " ").equals(right.trim().replaceAll("\\s+", " "));
    }

    private boolean containsProfileToken(Set<String> profiles, String... tokens) {
        for (String profile : profiles) {
            String lower = profile == null ? "" : profile.toLowerCase(Locale.ROOT);
            for (String token : tokens) {
                if (lower.contains(token)) return true;
            }
        }
        return false;
    }

    private boolean hasProbeType(AiResponseDTO dto, String... types) {
        for (AiResponseDTO.ExecutableWrongSolution probe : executableWrongSolutions(dto)) {
            String lower = probe.getType() == null ? "" : probe.getType().toLowerCase(Locale.ROOT);
            for (String type : types) {
                if (lower.contains(type)) return true;
            }
        }
        return false;
    }

    private boolean hasBugClass(AiResponseDTO dto, String... types) {
        if (dto == null || dto.getBugClasses() == null) return false;
        for (AiResponseDTO.BugClass bugClass : dto.getBugClasses()) {
            String lower = bugClass == null || bugClass.getName() == null ? "" : bugClass.getName().toLowerCase(Locale.ROOT);
            for (String type : types) {
                if (lower.contains(type)) return true;
            }
        }
        return false;
    }

    private boolean killsProbeType(AiResponseDTO dto, Set<String> killedNames, String... types) {
        for (AiResponseDTO.ExecutableWrongSolution probe : executableWrongSolutions(dto)) {
            String lower = probe.getType() == null ? "" : probe.getType().toLowerCase(Locale.ROOT);
            for (String type : types) {
                if (lower.contains(type) && killedNames.contains(probe.getName())) return true;
            }
        }
        return false;
    }

    private String normalizeProbeLanguage(String language) {
        if (language == null || language.isBlank()) return "cpp";
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("c++") || normalized.equals("cc") || normalized.equals("g++")) return "cpp";
        if (normalized.equals("py") || normalized.equals("python3")) return "python";
        return normalized;
    }

    private record GenerationOutcome(int savedCount, GenerationQualitySummary quality) {}

    private static class GeneratedTestcaseArtifactException extends IllegalStateException {
        private final boolean likelySchemaMismatch;
        private final boolean likelyGoldenReferenceFailure;

        GeneratedTestcaseArtifactException(String message, boolean likelySchemaMismatch,
                                           boolean likelyGoldenReferenceFailure) {
            super(message);
            this.likelySchemaMismatch = likelySchemaMismatch;
            this.likelyGoldenReferenceFailure = likelyGoldenReferenceFailure;
        }

        boolean likelySchemaMismatch() {
            return likelySchemaMismatch;
        }

        boolean likelyGoldenReferenceFailure() {
            return likelyGoldenReferenceFailure;
        }
    }

    private static class RejectionStats {
        int validatorRejected;
        int validatorUnexpectedExtraTokens;
        int generatorFailed;
        int goldenFailed;
        int duplicate;
        String lastGoldenFailure;

        void recordValidatorReject(String message) {
            validatorRejected++;
            if (message != null && message.toLowerCase(Locale.ROOT).contains("unexpected extra tokens")) {
                validatorUnexpectedExtraTokens++;
            }
        }

        void recordGoldenFailure(String message) {
            goldenFailed++;
            if (message != null && !message.isBlank()) {
                lastGoldenFailure = message;
            }
        }

        int totalRejected() {
            return validatorRejected + generatorFailed + goldenFailed + duplicate;
        }

        boolean likelySchemaMismatch() {
            return validatorUnexpectedExtraTokens > 0
                    && validatorUnexpectedExtraTokens >= Math.max(1, validatorRejected / 2);
        }

        boolean likelyGoldenReferenceFailure() {
            return goldenFailed > 0
                    && validatorRejected == 0
                    && generatorFailed == 0
                    && duplicate == 0;
        }

        String summary() {
            List<String> parts = new ArrayList<>();
            if (validatorRejected > 0) {
                String detail = validatorUnexpectedExtraTokens > 0
                        ? " (" + validatorUnexpectedExtraTokens + " unexpected-extra-token/schema mismatches)"
                        : "";
                parts.add("validator rejected " + validatorRejected + detail);
            }
            if (goldenFailed > 0) {
                String detail = lastGoldenFailure == null || lastGoldenFailure.isBlank()
                        ? ""
                        : " (last: " + lastGoldenFailure + ")";
                parts.add("golden solution failed " + goldenFailed + detail);
            }
            if (duplicate > 0) parts.add("duplicates " + duplicate);
            if (generatorFailed > 0) parts.add("generator failed/timed out " + generatorFailed);
            return parts.isEmpty() ? "No rejection details were recorded." : "Breakdown: " + String.join(", ", parts) + ".";
        }
    }

    static class GenerationQualitySummary {
        final Set<String> acceptedProfiles = new LinkedHashSet<>();
        final Set<String> killedProbeNames = new LinkedHashSet<>();
        Set<String> killedBySuite = Set.of();
        int bruteForceVerifiedCases;
        int minedProbeKillerCases;
        int deterministicAdversarialCases;
        boolean hasBruteForceArtifact;

        GenerationQualitySummary withKilledBySuite(Set<String> killedBySuite) {
            this.killedBySuite = killedBySuite == null ? Set.of() : new LinkedHashSet<>(killedBySuite);
            return this;
        }
    }

    record CoverageGateReport(Set<String> signals,
                              boolean hasBruteForceArtifact,
                              boolean hasBruteForceVerification,
                              boolean hasBoundaryCoverage,
                              boolean hasSmallCoverage,
                              boolean hasMediumCoverage,
                              boolean hasLargeOrStressCoverage,
                              boolean hasAdversarialCoverage,
                              boolean hasOverflowRisk,
                              boolean hasOverflowProbe,
                              boolean hasOverflowProfileCoverage,
                              boolean killsOverflowProbe,
                              boolean hasGreedyRisk,
                              boolean hasGreedyProbe,
                              boolean hasGreedyProfileCoverage,
                              boolean killsGreedyProbe) {}
}
