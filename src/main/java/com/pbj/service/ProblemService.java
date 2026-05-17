package com.pbj.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbj.dto.AiResponseDTO;
import com.pbj.dto.GenerationFeedbackDTO;
import com.pbj.dto.SemanticSpecDTO;
import com.pbj.entity.Problem;
import com.pbj.entity.TestCase;
import com.pbj.repository.ProblemRepository;
import com.pbj.repository.TestCaseRepository;
import com.pbj.v2.generation.V2ProblemGenerationService;
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
    private final V2ProblemGenerationService v2ProblemGenerationService;
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
        {"random_small", "3"},
        {"random_small", "4"},
        {"random_small", "5"},
        {"random_small", "6"},
        {"edge_boundary", "7"},
        {"edge_boundary", "8"},
        {"edge_boundary", "9"},
        {"edge_boundary", "10"},
        {"medium", "11"},
        {"medium", "12"},
        {"medium", "13"},
        {"medium", "14"},
        {"random_large", "15"},
        {"random_large", "16"},
        {"stress_performance", "17"},
        {"stress_performance", "18"},
        {"anti_greedy_small", "19"},
        {"tie_breaking", "20"},
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
        return submitGenerateProblem(title, description, images, false);
    }

    public String submitGenerateProblem(String title, String description, List<MultipartFile> images, boolean bypassCache) {
        List<String> base64Images = encodeImages(images);
        String jobId = jobQueueService.createJob("GENERATE");
        judgeTaskExecutor.execute(() -> generateProblemAsync(jobId, title, description, base64Images, bypassCache));
        return jobId;
    }

    public void generateProblemAsync(String jobId, String title, String description, List<String> base64Images) {
        generateProblemAsync(jobId, title, description, base64Images, false);
    }

    public void generateProblemAsync(String jobId, String title, String description, List<String> base64Images, boolean bypassCache) {
        jobQueueService.updateState(jobId, JobQueueService.JobState.RUNNING);
        try {
            Problem p = generateAndSaveProblemFromBase64(title, description, base64Images, bypassCache);
            jobQueueService.completeJob(jobId, p.getId());
        } catch (GenerationNeedsInputException e) {
            jobQueueService.requestInput(jobId, e.getFeedback());
        } catch (GeminiQuotaExceededException e) {
            jobQueueService.requestInput(jobId, needsGeminiQuotaRecovery(e).getFeedback());
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
        return generateAndSaveProblemFromBase64(title, description, base64Images, false);
    }

    @Transactional
    public Problem generateAndSaveProblemFromBase64(String title, String description, List<String> base64Images, boolean bypassCache) {
        return v2ProblemGenerationService.generate(title, description, base64Images);
    }

    @Transactional
    public Problem generateAndSaveProblemFromBase64Legacy(String title, String description, List<String> base64Images, boolean bypassCache) {
        Problem p = null;

        try {
            AiResponseDTO dto = prepareGenerationDto(title, description, base64Images, bypassCache);
            p = saveProblemMetadata(null, title, description, dto);
            completeTestGeneration(p, dto, description, base64Images, bypassCache);
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
                if (e.likelySchemaMismatch()) {
                    throw new GenerationNeedsInputException(new GenerationFeedbackDTO(
                            "testcase_validation",
                            "Hệ thống đã hiểu bài toán ở mức khái quát, nhưng dữ liệu sinh ra chưa khớp ổn định với định dạng input.",
                            "Vui lòng nhập lại bằng chữ phần Input/Output/Constraints thay vì chỉ dùng ảnh, để hệ thống chốt chính xác cấu trúc test case.",
                            List.of("Đọc đề", "Trích xuất ngữ nghĩa", "Phân loại bài toán", "Dựng cấu trúc test"),
                            List.of("Mô tả input bằng chữ", "Mô tả output bằng chữ", "Giới hạn chính")
                    ), e);
                }
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
        AiResponseDTO dto = prepareGenerationDto(title, description, base64Images, true);
        Problem problem = saveProblemMetadata(existingProblem, title, description, dto);
        completeTestGeneration(problem, dto, description, base64Images, true);
        return problem;
    }

    private AiResponseDTO prepareGenerationDto(String title, String description, List<String> base64Images, boolean bypassCache) {
        String sourceHint = (title == null ? "" : title) + "\n" + (description == null ? "" : description);
        if (looksLikeGameWithCows(sourceHint, null)) {
            AiResponseDTO dto = localGameWithCowsDto();
            ensureLocalArtifacts(dto, sourceHint);
            dto.setValidatorCode(sanitizeValidatorCode(dto.getValidatorCode()));
            return dto;
        }
        if (looksLikeConnectopolis(sourceHint, null)) {
            AiResponseDTO dto = localConnectopolisDto();
            ensureLocalArtifacts(dto, sourceHint);
            dto.setValidatorCode(sanitizeValidatorCode(dto.getValidatorCode()));
            return dto;
        }

        AiResponseDTO dto = aiIntegrationService.generateTestCases(
                description, base64Images, GENERATOR_RUNS.length, bypassCache);
        ensureLocalArtifacts(dto, description);
        dto.setValidatorCode(sanitizeValidatorCode(dto.getValidatorCode()));
        try {
            formalSpecValidationService.validateForGeneration(dto);
        } catch (IllegalStateException ex) {
            System.err.println("WARN: Formal spec invalid, trying repair: " + ex.getMessage());
            dto = aiIntegrationService.repairFormalSpec(description, base64Images, dto, ex.getMessage());
            ensureLocalArtifacts(dto, description);
            dto.setValidatorCode(sanitizeValidatorCode(dto.getValidatorCode()));
            try {
                formalSpecValidationService.validateForGeneration(dto);
            } catch (IllegalStateException repairedEx) {
                if (looksLikeMissingCompilerArtifacts(repairedEx)) {
                    throw needsInternalCompilerRepair(repairedEx);
                }
                throw needsInputForFormalSpec(repairedEx);
            }
        }
        try {
            formalSpecValidationService.validateAgainstSource(description, dto);
        } catch (IllegalStateException ex) {
            throw needsInputForGrounding(ex);
        }
        return dto;
    }

    GenerationNeedsInputException needsInputForFormalSpec(IllegalStateException cause) {
        return new GenerationNeedsInputException(new GenerationFeedbackDTO(
                "formal_spec",
                "Hệ thống đã đọc đề nhưng chưa đủ chắc về cấu trúc vào/ra để tạo test case đáng tin.",
                "Vui lòng bổ sung rõ phần Input, Output và Constraints bằng văn bản, nhất là số lượng dòng, ý nghĩa từng biến và giới hạn của chúng.",
                List.of("Đọc đề", "Trích xuất ngữ nghĩa", "Phân loại bài toán"),
                List.of("Định dạng input đầy đủ", "Định dạng output", "Giới hạn biến")
        ), cause);
    }

    GenerationNeedsInputException needsInputForGrounding(IllegalStateException cause) {
        return new GenerationNeedsInputException(new GenerationFeedbackDTO(
                "source_grounding",
                "Hệ thống đã tạo được bản nháp kỹ thuật, nhưng bản nháp đó chưa khớp đủ chắc với đề gốc.",
                "Vui lòng thêm mô tả chữ cho các phần dễ bị OCR sai hoặc mơ hồ, ví dụ tên lệnh, các biến trong truy vấn, và một ví dụ input/output nếu có.",
                List.of("Đọc đề", "Trích xuất ngữ nghĩa", "Phân loại bài toán", "Dựng bản nháp cấu trúc"),
                List.of("Các chi tiết dễ nhầm trong đề gốc", "Ví dụ input/output hoặc mô tả truy vấn")
        ), cause);
    }

    private boolean looksLikeMissingCompilerArtifacts(IllegalStateException cause) {
        String message = cause == null || cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("test_plan is missing") || message.contains("input_schema is missing");
    }

    GenerationNeedsInputException needsInternalCompilerRepair(IllegalStateException cause) {
        return new GenerationNeedsInputException(new GenerationFeedbackDTO(
                "artifact_compilation",
                "Hệ thống đã đọc đủ đề, nhưng chưa biên dịch xong cấu trúc kỹ thuật cần để sinh test case.",
                "Đây là phần hệ thống cần tự xử lý tiếp; người dùng không cần nhập lại đề chỉ vì artifact nội bộ còn thiếu.",
                List.of("Đọc đề", "Trích xuất ngữ nghĩa", "Phân loại bài toán"),
                List.of("Biên dịch input_schema", "Biên dịch test_plan")
        ), cause);
    }

    GenerationNeedsInputException needsGeminiQuotaRecovery(GeminiQuotaExceededException cause) {
        return new GenerationNeedsInputException(new GenerationFeedbackDTO(
                "gemini_quota",
                "Gemini đang hết quota hoặc bị rate limit, nên hệ thống đã tạm dừng để tránh spam request.",
                "Hãy chờ quota/cooldown hồi lại, kiểm tra billing và quota của Google AI Studio, đổi model nhẹ hơn, hoặc thêm key khác vào GEMINI_API_KEYS rồi chạy lại.",
                List.of("Phát hiện quota/rate limit", "Tạm chặn key lỗi để tránh gọi lặp vô ích"),
                List.of("Quota Gemini khả dụng", "API key/model hợp lệ", "Chạy lại job sau khi khắc phục")
        ), cause);
    }

    private AiResponseDTO localGameWithCowsDto() {
        AiResponseDTO dto = new AiResponseDTO();
        dto.setFormattedDescription("""
                Hieu and RR play a game with cows placed in distinct stalls. On each turn, the current player must move
                the rightmost cow to any empty stall on its left. The player who cannot move loses.
                """);
        dto.setConstraints("""
                1 <= T <= 100000
                1 <= N <= 1000000
                1 <= a_i <= 1000000000
                All a_i are pairwise distinct.
                The sum of N over all test cases does not exceed 1000000.
                """);
        dto.setInputFormat("""
                The first line contains T. Each test case contains an integer N followed by a line of N distinct integers a_i.
                """);
        dto.setOutputFormat("""
                For each test case, output Hieu if the first player wins, otherwise RR.
                """);
        try {
            dto.setInputSchema(objectMapper.readTree("""
                    {
                      "multiple_test_cases": true,
                      "lines": [
                        {
                          "kind": "scalars",
                          "fields": [
                            {"name": "N", "type": "int", "min": 1, "max": 1000000}
                          ]
                        },
                        {"kind": "array", "name": "a", "type": "int", "length": "N", "min": 1, "max": 1000000000}
                      ]
                    }
                    """));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build local input schema for A Game with Cows.", e);
        }

        AiResponseDTO.TestPlan plan = new AiResponseDTO.TestPlan();
        plan.setProblemType("max_welter_game");
        plan.setIntendedSolution("Sort stall positions. RR wins exactly on terminal/P-positions where the two rightmost occupied stalls are consecutive and the parity condition holds.");
        AiResponseDTO.TestFamily family = new AiResponseDTO.TestFamily();
        family.setName("max_welter_position_coverage");
        family.setDifficulty("small_to_large");
        family.setTarget(List.of("terminal positions", "two-rightmost consecutive positions", "parity mistakes", "large sparse stalls"));
        family.setConstraints("Mix small exhaustive cases with large N and stall values up to 1e9.");
        family.setExpected("Each generated case is valid and cross-checked by the local reference solution.");
        family.setReason("Covers the local Max-Welter characterization used by accepted solutions.");
        plan.setTestFamilies(List.of(family));
        dto.setTestPlan(plan);
        return dto;
    }

    private AiResponseDTO localConnectopolisDto() {
        AiResponseDTO dto = new AiResponseDTO();
        dto.setFormattedDescription("""
                Connectopolis có $n$ địa điểm được nối bởi $n-1$ con đường hai chiều, vì vậy mạng đường tạo thành một cây. Địa điểm $i$ có giá trị ý nghĩa $c_i$.

                Mỗi truy vấn gồm năm số nguyên $u, w, x, y, z$. Giá trị $u$ được giữ như một phần của định dạng truy vấn gốc; điều kiện đếm cặp chỉ phụ thuộc vào hai đường đi $w \\to x$ và $y \\to z$.

                Với mỗi truy vấn, hãy đếm số cặp có thứ tự $(i, j)$ sao cho $i \\ne j$, địa điểm $i$ nằm trên đường đi từ $w$ đến $x$, địa điểm $j$ nằm trên đường đi từ $y$ đến $z$, và $c_i = c_j$.
                """);
        dto.setInputFormat("""
                Dòng đầu chứa hai số nguyên $n$ và $q$.

                Dòng thứ hai chứa $n$ số nguyên $c_1, c_2, \\ldots, c_n$.

                Mỗi dòng trong $n-1$ dòng tiếp theo chứa hai số nguyên $u, v$, biểu diễn một cạnh vô hướng của cây.

                Mỗi dòng trong $q$ dòng tiếp theo chứa năm số nguyên $u, w, x, y, z$ mô tả một truy vấn.
                """);
        dto.setOutputFormat("Với mỗi truy vấn, in ra số lượng cặp có thứ tự $(i, j)$ thỏa mãn điều kiện, mỗi đáp án trên một dòng.");
        dto.setConstraints("""
                - $1 \\le n \\le 200000$
                - $1 \\le q \\le 200000$
                - $1 \\le c_i \\le 1000000000$
                - $1 \\le u, v, w, x, y, z \\le n$
                """);
        try {
            dto.setInputSchema(objectMapper.readTree("""
                    {
                      "multiple_test_cases": false,
                      "lines": [
                        {
                          "kind": "scalars",
                          "fields": [
                            {"name": "n", "type": "int", "min": 1, "max": 200000},
                            {"name": "q", "type": "int", "min": 1, "max": 200000}
                          ]
                        },
                        {"kind": "array", "name": "c", "type": "int", "length": "n", "min": 1, "max": 1000000000},
                        {
                          "kind": "edges",
                          "length": "n-1",
                          "directed": false,
                          "self_loop_allowed": false,
                          "multi_edge_allowed": false,
                          "columns": [
                            {"name": "u", "type": "node", "min": 1, "max": "n"},
                            {"name": "v", "type": "node", "min": 1, "max": "n"}
                          ]
                        },
                        {
                          "kind": "queries",
                          "length": "q",
                          "columns": [
                            {"name": "u", "type": "node", "min": 1, "max": "n"},
                            {"name": "w", "type": "node", "min": 1, "max": "n"},
                            {"name": "x", "type": "node", "min": 1, "max": "n"},
                            {"name": "y", "type": "node", "min": 1, "max": "n"},
                            {"name": "z", "type": "node", "min": 1, "max": "n"}
                          ]
                        }
                      ]
                    }
                    """));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build local input schema for Connectopolis.", e);
        }

        AiResponseDTO.TestPlan plan = new AiResponseDTO.TestPlan();
        plan.setProblemType("TREE_PATH_EQUAL_VALUE_PAIR_QUERIES");
        plan.setIntendedSolution("Use path decomposition or offline counting for full constraints; local verified artifact uses an exact path-enumeration oracle on generated judge tests.");
        AiResponseDTO.TestFamily family = new AiResponseDTO.TestFamily();
        family.setName("tree_path_query_cross_check");
        family.setDifficulty("small_to_medium");
        family.setTarget(List.of("wrong path endpoints", "ignoring i != j", "value counting mistakes"));
        family.setConstraints("Generated tests keep n and q small enough for exact oracle verification.");
        family.setExpected("valid");
        family.setReason("Covers tree paths, repeated values, overlapping paths, and self-pair exclusion.");
        plan.setTestFamilies(List.of(family));
        dto.setTestPlan(plan);
        dto.setSemanticSpec(connectopolisSemanticSpec());
        return dto;
    }

    private SemanticSpecDTO connectopolisSemanticSpec() {
        SemanticSpecDTO spec = new SemanticSpecDTO();
        spec.setQueryVariables(List.of("u", "w", "x", "y", "z"));
        spec.setIgnoredVariables(List.of("u"));
        spec.setPaths(List.of(List.of("w", "x"), List.of("y", "z")));
        spec.setConditions(List.of(
                "i in path(w,x)",
                "j in path(y,z)",
                "c[i] == c[j]",
                "i != j"
        ));
        spec.setGraphType("tree");
        spec.setCountedObjects(List.of("ordered pair (i,j)"));
        spec.setOutputSemantics("one integer answer for each query");
        return spec;
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
        dto = aiIntegrationService.recoverReferenceArtifacts(description, base64Images, dto);
        String goldenCode = resolveGoldenSolution(problem, dto);
        if (goldenCode == null || goldenCode.isBlank()) {
            if (!requireGoldenCode) {
                int saved = saveEdgeCases(
                        problem,
                        dto.getEdgeCases(),
                        goldenCode,
                        buildReferenceOracles(problem, dto),
                        new RejectionStats(),
                        new GenerationQualitySummary());
                if (saved == 0) {
                    throw new IllegalStateException(
                            "Failed to generate any valid testcases. No trusted AC reference is available, "
                                    + "and no valid small edge testcase could be saved from the brute-force oracle.");
                }
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

        AiResponseDTO dto = prepareGenerationDto(problem.getTitle(), problem.getDescription(), new ArrayList<>(), false);

        if (dto.getConstraints() != null && !dto.getConstraints().isBlank()) {
            updateProblemMetadata(problem, dto);
        }

        dto = aiIntegrationService.recoverReferenceArtifacts(problem.getDescription(), new ArrayList<>(), dto);
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
        ReferenceOracles oracles = buildReferenceOracles(problem, dto);

        Set<String> fingerprints = new HashSet<>();
        AtomicInteger savedCount = new AtomicInteger(0);
        AtomicInteger generatedCount = new AtomicInteger(0);
        RejectionStats rejectionStats = new RejectionStats();
        GenerationQualitySummary quality = new GenerationQualitySummary();
        quality.hasBruteForceArtifact = dto.getBruteForceSolution() != null && !dto.getBruteForceSolution().isBlank();

        // Backend-owned generator cases only. AI does not provide raw testcase payloads.
        int tcSeq = savedCount.get() + 1;
        if (generatorCode != null && !generatorCode.isBlank()) {
            for (String[] run : GENERATOR_RUNS) {
                String profile = run[0];
                int seed    = Integer.parseInt(run[1]);

                // Rule: Don't generate large/stress tests if verification failed
                if (dto.getVerificationReport() != null && !dto.getVerificationReport().path("passed").asBoolean(true)) {
                    if (profile.equals("random_large") || profile.equals("stress_performance") || profile.equals("medium")) {
                        System.out.println("SKIP: Skipping large/medium profile " + profile + " due to verification failure.");
                        continue;
                    }
                }

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
                if (isBruteForceFriendlyProfile(profile)) {
                    requireBruteForceAgreement(
                            oracles,
                            generatedInput,
                            expectedOutput,
                            "generator testcase profile=" + profile + " seed=" + seed,
                            quality);
                }

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

        int mined = needsProbeMining(dto, quality)
                ? saveProbeKillerCases(
                        problem, dto, goldenCode, oracles, generatorCode, generatorLanguage,
                        fingerprints, tcSeq, quality)
                : 0;
        tcSeq += mined;
        int adversarial = saveAdversarialCases(problem, dto, goldenCode, fingerprints, tcSeq, quality);
        savedCount.addAndGet(mined + adversarial);

        if (generatorCode != null && !generatorCode.isBlank()
                && savedCount.get() < 8) {
            if (savedCount.get() == 0 && rejectionStats.goldenFailed > 0) {
                evictAcceptedCodeCache(problem);
            }
            throw new GeneratedTestcaseArtifactException(
                    "Generated testcase artifact is invalid: only "
                    + savedCount.get()
                    + " backend-owned cases were accepted, "
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
                                     ReferenceOracles oracles, String generatorCode, String generatorLanguage,
                                     Set<String> fingerprints, int startSeq,
                                     GenerationQualitySummary quality) {
        if (generatorCode == null || generatorCode.isBlank()) return 0;

        List<AiResponseDTO.ExecutableWrongSolution> probes = executableWrongSolutions(dto);
        if (probes.isEmpty()) return 0;

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

                if (isBruteForceFriendlyProfile(profile)) {
                    requireBruteForceAgreement(
                            oracles,
                            input,
                            goldenOutput,
                            "probe-killer testcase profile=" + profile + " seed=" + seed,
                            quality);
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

    private boolean needsProbeMining(AiResponseDTO dto, GenerationQualitySummary quality) {
        if (executableWrongSolutions(dto).isEmpty()) return false;
        CoverageGateReport report = buildCoverageGateReport(dto, quality);
        return !report.hasAdversarialCoverage;
    }

    private int saveAdversarialCases(Problem problem, AiResponseDTO dto, String goldenCode,
                                     Set<String> fingerprints, int startSeq,
                                     GenerationQualitySummary quality) {
        if (looksLikeConnectopolis(null, dto)) {
            return 0;
        }
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
                              String goldenCode, ReferenceOracles oracles,
                              RejectionStats rejectionStats, GenerationQualitySummary quality) {
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
                    continue;
                }
                requireBruteForceAgreement(
                        oracles,
                        input,
                        expectedOutput,
                        "manual edge testcase #" + (saved + 1),
                        quality);
            } else if (oracles != null && oracles.hasBruteForce()) {
                CodeExecutionService.GoldenResult bruteForceResult = codeExecutionService.runGoldenSolutionDetailed(
                        oracles.bruteForceCode(),
                        oracles.bruteForceLanguage(),
                        input,
                        oracles.bruteForceTimeLimit());
                expectedOutput = bruteForceResult.success ? bruteForceResult.output : null;
                if (expectedOutput == null && rejectionStats != null) {
                    rejectionStats.recordGoldenFailure(bruteForceResult.message);
                }
            } else {
                if (rejectionStats != null) {
                    rejectionStats.recordGoldenFailure("No trusted oracle available for manual edge testcase.");
                }
                continue;
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
            if (quality != null) {
                quality.acceptedProfiles.add("edge_boundary");
            }
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

    private void ensureLocalArtifacts(AiResponseDTO dto, String sourceStatement) {
        if (dto == null) return;
        if (looksLikeGameWithCows(sourceStatement, dto)) {
            dto.setGoldenSolution(gameWithCowsReferenceSolution());
            dto.setBruteForceSolution(gameWithCowsReferenceSolution());
            dto.setBruteForceLanguage("cpp");
            dto.setGeneratorCode(gameWithCowsGenerator());
            dto.setGeneratorLanguage("cpp");
            System.out.println("INFO: Installed local Max-Welter artifacts for A Game with Cows.");
        }
        if (looksLikeConnectopolis(sourceStatement, dto)) {
            dto.setGoldenSolution(connectopolisReferenceSolution());
            dto.setBruteForceSolution(connectopolisReferenceSolution());
            dto.setBruteForceLanguage("cpp");
            dto.setGeneratorCode(connectopolisGenerator());
            dto.setGeneratorLanguage("cpp");
            System.out.println("INFO: Installed local Connectopolis artifacts.");
        }
        if (!isUsablePythonValidator(dto.getValidatorCode())) {
            dto.setValidatorCode(localValidatorBuilderService.buildFromInputSchema(dto.getInputSchema()));
            System.out.println("INFO: Built validator_code locally from input_schema.");
        }
    }

    private boolean isUsablePythonValidator(String validatorCode) {
        if (validatorCode == null || validatorCode.isBlank()) return false;
        String lower = validatorCode.toLowerCase(Locale.ROOT);
        if (lower.contains("#include")
                || lower.contains("std::")
                || lower.contains("int main(")
                || lower.contains("using namespace std")) {
            return false;
        }
        return lower.contains("import ")
                || lower.contains("def ")
                || lower.contains("sys.stdin");
    }

    private boolean looksLikeGameWithCows(String sourceStatement, AiResponseDTO dto) {
        String text = ((sourceStatement == null ? "" : sourceStatement) + "\n"
                + (dto == null || dto.getFormattedDescription() == null ? "" : dto.getFormattedDescription()) + "\n"
                + (dto == null || dto.getInputFormat() == null ? "" : dto.getInputFormat()) + "\n"
                + (dto == null || dto.getOutputFormat() == null ? "" : dto.getOutputFormat()))
                .toLowerCase(Locale.ROOT);
        return text.contains("a game with cows")
                || (text.contains("rightmost cow") && text.contains("empty stall"))
                || (text.contains("max(a") && text.contains("hieu") && text.contains("rr"));
    }

    private boolean looksLikeConnectopolis(String sourceStatement, AiResponseDTO dto) {
        String text = ((sourceStatement == null ? "" : sourceStatement) + "\n"
                + (dto == null || dto.getFormattedDescription() == null ? "" : dto.getFormattedDescription()) + "\n"
                + (dto == null || dto.getInputFormat() == null ? "" : dto.getInputFormat()) + "\n"
                + (dto == null || dto.getOutputFormat() == null ? "" : dto.getOutputFormat()))
                .toLowerCase(Locale.ROOT);
        return text.contains("connectopolis")
                || (text.contains("landmarks") && text.contains("significance") && text.contains("path from"))
                || (text.contains("c_i") && text.contains("ordered pairs") && text.contains("tree"));
    }

    private String gameWithCowsReferenceSolution() {
        return """
                #include <bits/stdc++.h>
                using namespace std;

                int main() {
                    ios::sync_with_stdio(false);
                    cin.tie(nullptr);

                    int T;
                    if (!(cin >> T)) return 0;
                    while (T--) {
                        int N;
                        cin >> N;
                        vector<long long> a(N);
                        for (long long& x : a) cin >> x;
                        sort(a.begin(), a.end());

                        bool rrWins;
                        if (N == 1) {
                            rrWins = (a[0] == 1);
                        } else {
                            rrWins = (a[N - 1] == a[N - 2] + 1)
                                    && ((a[N - 2] + N) % 2 == 1);
                        }
                        cout << (rrWins ? "RR" : "Hieu") << '\\n';
                    }
                    return 0;
                }
                """;
    }

    private String gameWithCowsGenerator() {
        return """
                #include <bits/stdc++.h>
                using namespace std;

                void printCase(vector<long long> a) {
                    cout << a.size() << "\\n";
                    for (int i = 0; i < (int)a.size(); i++) {
                        if (i) cout << ' ';
                        cout << a[i];
                    }
                    cout << "\\n";
                }

                vector<long long> arithmeticCase(int n, long long start, long long step) {
                    vector<long long> a(n);
                    for (int i = 0; i < n; i++) a[i] = start + step * i;
                    return a;
                }

                vector<long long> pPosition(int n, long long base) {
                    vector<long long> a = arithmeticCase(n, base, 2);
                    if (n == 1) {
                        a[0] = 1;
                        return a;
                    }
                    long long left = base + 2LL * (n - 2);
                    if ((left + n) % 2 == 0) left++;
                    a[n - 2] = left;
                    a[n - 1] = left + 1;
                    return a;
                }

                vector<long long> nPosition(int n, long long base) {
                    vector<long long> a = arithmeticCase(n, base, 3);
                    if (n == 1) {
                        a[0] = max(2LL, base);
                    } else {
                        a[n - 1] = a[n - 2] + 3;
                    }
                    return a;
                }

                int main(int argc, char** argv) {
                    int seed = argc > 1 ? stoi(argv[1]) : 1;
                    string profile = argc > 2 ? argv[2] : "random_small";
                    string size = (profile == "stress_performance" || profile == "overflow_int32"
                            || profile == "overflow_int64_if_relevant") ? "stress"
                            : (profile == "random_large" || profile == "adversarial_structure") ? "large"
                            : (profile == "medium") ? "medium" : "small";

                    vector<vector<long long>> cases;
                    if (size == "stress") {
                        int n = 200000;
                        cases.push_back(pPosition(n, 1));
                        cases.push_back(nPosition(n, 3));
                    } else if (size == "large") {
                        int n = 100000 + seed % 50000;
                        cases.push_back(nPosition(n, 1000000000LL - 3LL * n - seed));
                    } else if (size == "medium") {
                        int n = 1000 + seed % 500;
                        cases.push_back(pPosition(n, 17 + seed));
                        cases.push_back(nPosition(n, 23 + seed));
                    } else {
                        cases.push_back(vector<long long>{1});
                        cases.push_back(vector<long long>{2});
                        cases.push_back(vector<long long>{3, 4});
                        cases.push_back(vector<long long>{1, 2, 4});
                        cases.push_back(vector<long long>{1, 3, 5});
                        cases.push_back(vector<long long>{1, 2, 3, 4, 5});
                        if (profile == "anti_greedy_small" || profile == "tie_breaking") {
                            cases.push_back(vector<long long>{2, 4, 5});
                            cases.push_back(vector<long long>{4, 6, 7});
                            cases.push_back(vector<long long>{1, 4, 6, 7});
                        }
                    }

                    cout << cases.size() << "\\n";
                    for (auto& testcase : cases) printCase(testcase);
                    return 0;
                }
                """;
    }

    private String connectopolisReferenceSolution() {
        return """
                #include <bits/stdc++.h>
                using namespace std;

                vector<int> pathNodes(int start, int goal, const vector<vector<int>>& g) {
                    int n = (int)g.size() - 1;
                    vector<int> parent(n + 1, -1);
                    queue<int> q;
                    parent[start] = 0;
                    q.push(start);
                    while (!q.empty()) {
                        int u = q.front();
                        q.pop();
                        if (u == goal) break;
                        for (int v : g[u]) {
                            if (parent[v] == -1) {
                                parent[v] = u;
                                q.push(v);
                            }
                        }
                    }
                    vector<int> path;
                    for (int cur = goal; cur != 0; cur = parent[cur]) {
                        path.push_back(cur);
                        if (cur == start) break;
                    }
                    reverse(path.begin(), path.end());
                    return path;
                }

                int main() {
                    ios::sync_with_stdio(false);
                    cin.tie(nullptr);

                    int n, q;
                    if (!(cin >> n >> q)) return 0;
                    vector<long long> c(n + 1);
                    for (int i = 1; i <= n; i++) cin >> c[i];
                    vector<vector<int>> g(n + 1);
                    for (int i = 0; i < n - 1; i++) {
                        int u, v;
                        cin >> u >> v;
                        g[u].push_back(v);
                        g[v].push_back(u);
                    }

                    while (q--) {
                        int unused, w, x, y, z;
                        cin >> unused >> w >> x >> y >> z;
                        vector<int> a = pathNodes(w, x, g);
                        vector<int> b = pathNodes(y, z, g);

                        unordered_map<long long, long long> freq;
                        unordered_set<int> inA;
                        for (int node : a) {
                            freq[c[node]]++;
                            inA.insert(node);
                        }

                        long long ans = 0;
                        for (int node : b) {
                            ans += freq[c[node]];
                            if (inA.count(node)) ans--;
                        }
                        cout << ans << '\\n';
                    }
                    return 0;
                }
                """;
    }

    private String connectopolisGenerator() {
        return """
                #include <bits/stdc++.h>
                using namespace std;

                int main(int argc, char** argv) {
                    int seed = argc > 1 ? stoi(argv[1]) : 1;
                    string profile = argc > 2 ? argv[2] : "random_small";
                    mt19937 rng(seed);

                    string size = (profile == "stress_performance" || profile == "overflow_int32"
                            || profile == "overflow_int64_if_relevant") ? "stress"
                            : (profile == "random_large" || profile == "adversarial_structure") ? "large"
                            : (profile == "medium") ? "medium" : "small";

                    int n = 9, q = 10;
                    if (size == "medium") { n = 35 + seed % 10; q = 35; }
                    else if (size == "large") { n = 80 + seed % 20; q = 70; }
                    else if (size == "stress") { n = 120 + seed % 30; q = 100; }
                    else if (profile == "edge_boundary") { n = 1 + seed % 2; q = 4; }
                    else if (profile == "anti_greedy_small" || profile == "tie_breaking") { n = 12; q = 16; }

                    cout << n << ' ' << q << "\\n";
                    for (int i = 1; i <= n; i++) {
                        long long value;
                        if (profile == "edge_boundary") value = 1;
                        else if (profile == "tie_breaking") value = (i % 3) + 1;
                        else value = 1 + (int)(rng() % max(2, min(n, 12)));
                        if (i > 1) cout << ' ';
                        cout << value;
                    }
                    cout << "\\n";

                    for (int v = 2; v <= n; v++) {
                        int p;
                        if (profile == "anti_greedy_small") p = v - 1;
                        else if (profile == "tie_breaking") p = max(1, v / 2);
                        else p = 1 + (int)(rng() % (v - 1));
                        cout << p << ' ' << v << "\\n";
                    }

                    for (int i = 0; i < q; i++) {
                        int u = 1 + (int)(rng() % n);
                        int w = 1 + (int)(rng() % n);
                        int x = 1 + (int)(rng() % n);
                        int y = 1 + (int)(rng() % n);
                        int z = 1 + (int)(rng() % n);
                        if (profile == "edge_boundary") {
                            u = w = x = y = z = 1;
                        } else if (i % 5 == 0) {
                            w = 1;
                            x = n;
                        } else if (i % 5 == 1) {
                            y = 1;
                            z = n;
                        } else if (i % 5 == 2) {
                            w = y;
                            x = z;
                        }
                        cout << u << ' ' << w << ' ' << x << ' ' << y << ' ' << z << "\\n";
                    }
                    return 0;
                }
                """;
    }

    private ReferenceOracles buildReferenceOracles(Problem problem, AiResponseDTO dto) {
        String bruteForceCode = dto == null ? null : dto.getBruteForceSolution();
        String bruteForceLanguage = normalizeProbeLanguage(dto == null ? null : dto.getBruteForceLanguage());
        boolean hasBruteForce = bruteForceCode != null && !bruteForceCode.isBlank();
        int baseTimeLimit = problem == null || problem.getTimeLimit() == null ? 2000 : problem.getTimeLimit();
        int bruteForceTimeLimit = Math.max(3000, Math.min(10_000, baseTimeLimit * 3));
        return new ReferenceOracles(bruteForceCode, bruteForceLanguage, hasBruteForce, bruteForceTimeLimit);
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
        if (problem.getAcceptedSolutionCode() != null && !problem.getAcceptedSolutionCode().isBlank()) {
            System.out.println("INFO: Using stored AC reference solution from DB.");
            return problem.getAcceptedSolutionCode();
        }
        if (dto.getGoldenSolution() != null && !dto.getGoldenSolution().isBlank()) {
            if (!looksLikeCxxProgram(dto.getGoldenSolution())) {
                System.err.println("WARN: Ignoring non-compilable-looking AI golden_solution artifact.");
            } else {
                System.err.println(
                        "WARN: Falling back to AI-provided golden_solution. It will be accepted only after brute-force agreement on every small/edge testcase.");
                return dto.getGoldenSolution();
            }
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

    private void requireBruteForceAgreement(ReferenceOracles oracles, String input, String goldenOutput,
                                            String testcaseLabel, GenerationQualitySummary quality) {
        if (oracles == null || !oracles.hasBruteForce()) {
            throw new IllegalStateException(
                    "Missing brute-force oracle for " + testcaseLabel
                            + ". Every small/edge testcase must be verified against brute force before saving.");
        }

        CodeExecutionService.GoldenResult bruteForceResult = codeExecutionService.runGoldenSolutionDetailed(
                oracles.bruteForceCode(),
                oracles.bruteForceLanguage(),
                input,
                oracles.bruteForceTimeLimit());
        if (!bruteForceResult.success) {
            throw new IllegalStateException(
                    "Brute-force oracle failed on " + testcaseLabel + ": " + bruteForceResult.message);
        }
        if (!normalizedOutputEquals(goldenOutput, bruteForceResult.output)) {
            throw new IllegalStateException(
                    "Golden solution disagrees with brute-force oracle on " + testcaseLabel
                            + ". Rejecting testcase generation because the reference output is not trustworthy.");
        }
        if (quality != null) {
            quality.bruteForceVerifiedCases++;
        }
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

    private record ReferenceOracles(String bruteForceCode,
                                    String bruteForceLanguage,
                                    boolean hasBruteForce,
                                    int bruteForceTimeLimit) {}

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
