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
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AiIntegrationService {

    private final AiCacheRepository aiCacheRepository;
    private final AiJobQueueService aiJobQueueService;
    private final OllamaAnalysisService ollamaAnalysisService;
    private final GeminiTestGenerationService geminiTestGenerationService;
    private final ObjectMapper objectMapper;

    public AiResponseDTO generateTestCases(String problemDescription, List<String> base64Images, int count) {
        return generateTestCases(problemDescription, base64Images, count, false);
    }

    public AiResponseDTO generateTestCases(String problemDescription, List<String> base64Images, int count, boolean bypassCache) {
        String sourceFingerprint = sourceFingerprint(problemDescription, base64Images);
        String cacheKey = "ollama_gemini_pipeline_v3_" + generateHash(sourceFingerprint + "|count=" + count);

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

        String geminiKey = "gemini_artifacts_v2_" + generateHash(normalizedProblemText + "|" + analysisJson + "|count=" + count);
        Optional<AiResponseDTO> cachedGeminiDto = readCache(geminiKey, AiResponseDTO.class, "gemini-artifacts");
        AiResponseDTO dto;
        if (!bypassCache && cachedGeminiDto.isPresent()) {
            dto = cachedGeminiDto.get();
        } else {
            System.out.println("INFO: [AI Pipeline] Step 2 - Gemini test generation...");
            dto = geminiTestGenerationService.generateTestArtifacts(problemText, analysisJson, count);
            saveCache(geminiKey, dto, "gemini-artifacts");
        }

        if (!bypassCache) {
            saveCache(cacheKey, dto, "pipeline");
        }
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
        String pipelineKey = "ollama_gemini_pipeline_v3_" + generateHash(sourceFingerprint + "|count=" + count);
        evictCache(pipelineKey, "pipeline");

        String problemText = resolveProblemTextForCacheEviction(problemDescription, base64Images, sourceFingerprint);
        String normalizedProblemText = normalizeProblemText(problemText);
        String analysisKey = "ollama_analysis_v2_" + generateHash(normalizedProblemText);
        Optional<String> cachedAnalysis = readCache(analysisKey, String.class, "ollama-analysis-evict-lookup");
        if (cachedAnalysis.isPresent()) {
            String geminiKey = "gemini_artifacts_v2_" + generateHash(
                    normalizedProblemText + "|" + cachedAnalysis.get() + "|count=" + count);
            evictCache(geminiKey, "gemini-artifacts");
        }
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
}
