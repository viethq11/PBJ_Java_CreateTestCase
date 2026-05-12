package com.pbj.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbj.dto.AiResponseDTO;
import com.pbj.entity.AiCache;
import com.pbj.repository.AiCacheRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AiIntegrationService {

    private final AiCacheRepository aiCacheRepository;
    private final AiJobQueueService aiJobQueueService;
    private final OllamaAnalysisService ollamaAnalysisService;
    private final OllamaGeneratorService ollamaGeneratorService;
    private final GeminiTestGenerationService geminiTestGenerationService;
    private final ObjectMapper objectMapper;

    public AiResponseDTO generateTestCases(String problemDescription, List<String> base64Images, int count) {
        return generateTestCases(problemDescription, base64Images, count, false);
    }

    public AiResponseDTO generateTestCases(String problemDescription, List<String> base64Images, int count, boolean bypassCache) {
        String sourceFingerprint = sourceFingerprint(problemDescription, base64Images);
        String cacheKey = pipelineCacheKey(sourceFingerprint, count);

        Optional<AiResponseDTO> cachedDto = readCache(cacheKey, AiResponseDTO.class, "pipeline");
        if (!bypassCache && cachedDto.isPresent()) {
            return cachedDto.get();
        }

        if (bypassCache) {
            System.out.println("INFO: AI Cache bypass requested for final pipeline only. Hash: " + cacheKey);
        }

        return aiJobQueueService.runQueued("pipeline:" + cacheKey, () -> generateTestCasesQueued(
                problemDescription, base64Images, count, bypassCache, sourceFingerprint, cacheKey));
    }

    private AiResponseDTO generateTestCasesQueued(String problemDescription, List<String> base64Images, int count,
                                                  boolean bypassCache, String sourceFingerprint, String cacheKey) {
        Optional<AiResponseDTO> cachedDto = readCache(cacheKey, AiResponseDTO.class, "pipeline-after-queue");
        if (!bypassCache && cachedDto.isPresent()) {
            return cachedDto.get();
        }

        String problemText = resolveProblemText(problemDescription, base64Images, sourceFingerprint);
        String normalizedProblemText = normalizeProblemText(problemText);
        String analysisJson = resolveAnalysisJson(normalizedProblemText);

        String geminiKey = "gemini_artifacts_v3_no_generator_" + generateHash(normalizedProblemText + "|" + analysisJson + "|count=" + count);
        Optional<AiResponseDTO> cachedGeminiDto = readCache(geminiKey, AiResponseDTO.class, "gemini-artifacts");
        AiResponseDTO dto;
        if (!bypassCache && cachedGeminiDto.isPresent()) {
            dto = cachedGeminiDto.get();
        } else {
            System.out.println("INFO: [AI Pipeline] Step 2 - Gemini test generation...");
            dto = geminiTestGenerationService.generateTestArtifacts(problemText, analysisJson, count);
            saveCache(geminiKey, dto, "gemini-artifacts");
        }

        String generatorSpecJson = buildGeneratorSpecJson(normalizedProblemText, analysisJson, dto);
        System.out.println("INFO: [AI Pipeline] Step 3 - Ollama local C++ generator...");
        String generatorCode = ollamaGeneratorService.generateGenerator(generatorSpecJson);
        dto.setGeneratorCode(generatorCode);
        dto.setGeneratorLanguage("cpp");

        return dto;
    }

    public String generateAcceptedCode(String title, String description, String inputFormat,
                                       String outputFormat, String constraints, String language) {
        return generateCodeWithCache(title, description, inputFormat, outputFormat, constraints, language, "AC");
    }

    public String generateWrongAnswerCode(String title, String description, String inputFormat,
                                          String outputFormat, String constraints, String language) {
        return generateCodeWithCache(title, description, inputFormat, outputFormat, constraints, language, "WA");
    }

    public String generateTimeLimitExceededCode(String title, String description, String inputFormat,
                                                String outputFormat, String constraints, String language) {
        return generateCodeWithCache(title, description, inputFormat, outputFormat, constraints, language, "TLE");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void evictTestGenerationCache(String problemDescription, List<String> base64Images, int count) {
        String sourceFingerprint = sourceFingerprint(problemDescription, base64Images);
        String pipelineKey = pipelineCacheKey(sourceFingerprint, count);
        evictCache(pipelineKey, "pipeline");

        String problemText = resolveProblemTextForCacheEviction(problemDescription, base64Images, sourceFingerprint);
        String normalizedProblemText = normalizeProblemText(problemText);
        String analysisKey = "ollama_analysis_v2_" + generateHash(normalizedProblemText);
        Optional<String> cachedAnalysis = readCache(analysisKey, String.class, "ollama-analysis-evict-lookup");
        if (cachedAnalysis.isPresent()) {
            String geminiKey = "gemini_artifacts_v3_no_generator_" + generateHash(
                    normalizedProblemText + "|" + cachedAnalysis.get() + "|count=" + count);
            evictCache(geminiKey, "gemini-artifacts");
        }
    }

    public String repairGenerator(AiResponseDTO dto, String previousGenerator, String validationError) {
        String generatorSpecJson = buildGeneratorSpecJson(
                normalizeProblemText(dto.getFormattedDescription()),
                "{}",
                dto);
        return aiJobQueueService.runQueued("ollama-generator-repair", () ->
                ollamaGeneratorService.repairGenerator(generatorSpecJson, previousGenerator, validationError));
    }

    public void saveValidatedTestGeneration(String problemDescription, List<String> base64Images,
                                            int count, AiResponseDTO dto) {
        String sourceFingerprint = sourceFingerprint(problemDescription, base64Images);
        saveCache(pipelineCacheKey(sourceFingerprint, count), dto, "validated-pipeline");
    }

    private String resolveProblemText(String problemDescription, List<String> base64Images, String sourceFingerprint) {
        if (base64Images == null || base64Images.isEmpty()) {
            return problemDescription != null ? problemDescription : "";
        }

        String ocrKey = "problem_text_v1_" + generateHash(sourceFingerprint);
        Optional<String> cachedText = readCache(ocrKey, String.class, "problem-text");
        if (cachedText.isPresent()) {
            return cachedText.get();
        }

        System.out.println("INFO: [AI Pipeline] OCR - Gemini extracting problem text from image...");
        String extractedText = geminiTestGenerationService.extractProblemText(problemDescription, base64Images);
        if (extractedText == null || extractedText.isBlank()) {
            System.err.println("WARN: Gemini OCR failed; falling back to text description.");
            return problemDescription != null ? problemDescription : "(problem from attached image)";
        }
        saveCache(ocrKey, extractedText, "problem-text");
        return extractedText;
    }

    private String resolveProblemTextForCacheEviction(String problemDescription, List<String> base64Images, String sourceFingerprint) {
        if (base64Images == null || base64Images.isEmpty()) {
            return problemDescription != null ? problemDescription : "";
        }

        String ocrKey = "problem_text_v1_" + generateHash(sourceFingerprint);
        Optional<String> cachedText = readCache(ocrKey, String.class, "problem-text-evict-lookup");
        return cachedText.orElse(problemDescription != null ? problemDescription : "");
    }

    private String resolveAnalysisJson(String normalizedProblemText) {
        String analysisKey = "ollama_analysis_v2_" + generateHash(normalizedProblemText);
        Optional<String> cachedAnalysis = readCache(analysisKey, String.class, "ollama-analysis");
        if (cachedAnalysis.isPresent()) {
            return cachedAnalysis.get();
        }

        System.out.println("INFO: [AI Pipeline] Step 1 - Ollama local analysis...");
        String analysisJson = ollamaAnalysisService.analyzeProblem(normalizedProblemText);
        saveCache(analysisKey, analysisJson, "ollama-analysis");
        return analysisJson;
    }

    private String generateCodeWithCache(String title, String description, String inputFormat,
                                         String outputFormat, String constraints, String language, String type) {
        String cacheKey = "code_" + type + "_" + generateHash(title + description + inputFormat + outputFormat + constraints + language);
        Optional<AiCache> cached = aiCacheRepository.findByRequestHash(cacheKey);
        if (cached.isPresent()) {
            try {
                System.out.println("INFO: AI Cache Hit for code. Hash: " + cacheKey);
                return objectMapper.readValue(cached.get().getResponse(), String.class);
            } catch (Exception e) {
                System.err.println("WARN: Failed to read code from AI Cache: " + e.getMessage());
            }
        }

        return aiJobQueueService.runQueued("code:" + type + ":" + cacheKey, () -> generateCodeQueued(
                title, description, inputFormat, outputFormat, constraints, language, type, cacheKey));
    }

    private String generateCodeQueued(String title, String description, String inputFormat,
                                      String outputFormat, String constraints, String language,
                                      String type, String cacheKey) {
        Optional<String> cachedAfterQueue = readCache(cacheKey, String.class, "code-after-queue");
        if (cachedAfterQueue.isPresent()) {
            return cachedAfterQueue.get();
        }

        String code = geminiTestGenerationService.generateCode(
                title, description, inputFormat, outputFormat, constraints, language, type);
        try {
            AiCache cacheEntry = new AiCache();
            cacheEntry.setRequestHash(cacheKey);
            cacheEntry.setResponse(objectMapper.writeValueAsString(code));
            aiCacheRepository.save(cacheEntry);
        } catch (Exception e) {
            System.err.println("WARN: Failed to save generated code to AI Cache: " + e.getMessage());
        }
        return code;
    }

    private <T> Optional<T> readCache(String cacheKey, Class<T> valueType, String label) {
        Optional<AiCache> cached = aiCacheRepository.findByRequestHash(cacheKey);
        if (cached.isEmpty()) {
            System.out.println("INFO: AI Cache Miss for " + label + ". Hash: " + cacheKey);
            return Optional.empty();
        }

        try {
            System.out.println("INFO: AI Cache Hit for " + label + ". Hash: " + cacheKey);
            return Optional.of(objectMapper.readValue(cached.get().getResponse(), valueType));
        } catch (Exception e) {
            System.err.println("WARN: Failed to read " + label + " from AI Cache: " + e.getMessage());
            return Optional.empty();
        }
    }

    private void saveCache(String cacheKey, Object value, String label) {
        try {
            AiCache cacheEntry = aiCacheRepository.findByRequestHash(cacheKey).orElse(new AiCache());
            cacheEntry.setRequestHash(cacheKey);
            cacheEntry.setResponse(objectMapper.writeValueAsString(value));
            aiCacheRepository.save(cacheEntry);
            System.out.println("INFO: AI Cache Saved for " + label + ". Hash: " + cacheKey);
        } catch (Exception e) {
            System.err.println("WARN: Failed to save " + label + " to AI Cache: " + e.getMessage());
        }
    }

    private void evictCache(String cacheKey, String label) {
        try {
            aiCacheRepository.deleteByRequestHash(cacheKey);
            System.out.println("INFO: AI Cache Evicted for " + label + ". Hash: " + cacheKey);
        } catch (Exception e) {
            System.err.println("WARN: Failed to evict " + label + " from AI Cache: " + e.getMessage());
        }
    }

    private String buildGeneratorSpecJson(String problemText, String analysisJson, AiResponseDTO dto) {
        try {
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("problem_type", dto.getTestPlan() != null ? dto.getTestPlan().getProblemType() : "");
            spec.put("constraints", dto.getConstraints());
            spec.put("input_format", dto.getInputFormat());
            spec.put("output_format", dto.getOutputFormat());
            spec.put("analysis_json", analysisJson == null ? "{}" : analysisJson);
            spec.put("test_plan", dto.getTestPlan());
            spec.put("validator_rules", dto.getValidatorRules());
            spec.put("size_profiles", Map.of(
                    "small", "tiny valid testcase, preferably N <= 10 when applicable",
                    "medium", "moderate valid testcase",
                    "large", "near upper constraints but must finish under 1 second",
                    "stress", "adversarial near max constraints, valid and deterministic"
            ));
            spec.put("safety_contract", Map.of(
                    "node_indexing", "Use 1-based nodes unless the input_format explicitly says otherwise.",
                    "no_node_zero", true,
                    "binary_values", "If a field is binary, output only 0 or 1.",
                    "no_unbounded_retry_loops", true,
                    "no_invalid_input", true
            ));
            spec.put("problem_excerpt", problemText == null ? "" : problemText.substring(0, Math.min(problemText.length(), 4000)));
            return objectMapper.writeValueAsString(spec);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build generator spec JSON", e);
        }
    }

    private String generateHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new RuntimeException("Could not generate hash", e);
        }
    }

    private String normalizeProblemText(String input) {
        if (input == null) return "";
        return input.replace("\r\n", "\n")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String imageFingerprint(List<String> base64Images) {
        if (base64Images == null || base64Images.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String image : base64Images) {
            sb.append(generateHash(image == null ? "" : image)).append(';');
        }
        return sb.toString();
    }

    private String sourceFingerprint(String problemDescription, List<String> base64Images) {
        return normalizeProblemText(problemDescription) + "|images=" + imageFingerprint(base64Images);
    }

    private String pipelineCacheKey(String sourceFingerprint, int count) {
        return "ollama_gemini_ollama_generator_pipeline_v5_"
                + generateHash(sourceFingerprint + "|count=" + count);
    }
}
