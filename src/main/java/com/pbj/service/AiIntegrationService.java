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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AiIntegrationService {
    private static final String ANALYSIS_CACHE_PREFIX = "ollama_analysis_v6_grounded_";
    private static final String GEMINI_CACHE_PREFIX = "gemini_artifacts_v8_grounded_profiles_";
    private static final String PIPELINE_CACHE_PREFIX = "ollama_gemini_ollama_generator_pipeline_v10_grounded_profiles_";

    private final AiCacheRepository aiCacheRepository;
    private final AiJobQueueService aiJobQueueService;
    private final OllamaAnalysisService ollamaAnalysisService;
    private final OllamaGeneratorService ollamaGeneratorService;
    private final GeminiTestGenerationService geminiTestGenerationService;
    private final FormalSpecValidationService formalSpecValidationService;
    private final SystemTestcaseGeneratorService systemTestcaseGeneratorService;
    private final ObjectMapper objectMapper;

    public AiResponseDTO generateTestCases(String problemDescription, List<String> base64Images, int count) {
        return generateTestCases(problemDescription, base64Images, count, false);
    }

    public AiResponseDTO generateTestCases(String problemDescription, List<String> base64Images, int count, boolean bypassCache) {
        String sourceFingerprint = sourceFingerprint(problemDescription, base64Images);
        String cacheKey = pipelineCacheKey(sourceFingerprint, count);

        Optional<AiResponseDTO> cachedDto = readCache(cacheKey, AiResponseDTO.class, "pipeline");
        if (!bypassCache && cachedDto.isPresent()) {
            return normalizeArtifacts(cachedDto.get());
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
            return normalizeArtifacts(cachedDto.get());
        }

        String problemText = resolveProblemText(problemDescription, base64Images, sourceFingerprint);
        String normalizedProblemText = normalizeProblemText(problemText);
        String analysisJson = resolveAnalysisJson(normalizedProblemText);

        String geminiKey = GEMINI_CACHE_PREFIX + generateHash(normalizedProblemText + "|" + analysisJson + "|count=" + count);
        Optional<AiResponseDTO> cachedGeminiDto = readCache(geminiKey, AiResponseDTO.class, "gemini-artifacts");
        AiResponseDTO dto;
        if (!bypassCache && cachedGeminiDto.isPresent()) {
            dto = normalizeArtifacts(cachedGeminiDto.get());
        } else {
            System.out.println("INFO: [AI Pipeline] Step 2 - Gemini test generation...");
            dto = normalizeArtifacts(geminiTestGenerationService.generateTestArtifacts(problemText, analysisJson, count));
            saveCache(geminiKey, dto, "gemini-artifacts");
        }
        formalSpecValidationService.validateAgainstSource(normalizedProblemText, dto);

        System.out.println("INFO: [AI Pipeline] Step 3 - System testcase generator...");
        String generatorCode = systemTestcaseGeneratorService.buildGenerator(dto, normalizedProblemText);
        dto.setGeneratorCode(generatorCode);
        dto.setGeneratorLanguage("cpp");

        return dto;
    }

    private AiResponseDTO normalizeArtifacts(AiResponseDTO dto) {
        if (dto != null && dto.getInputSchema() != null) {
            dto.setInputSchema(geminiTestGenerationService.normalizeInputSchema(dto.getInputSchema()));
        }
        return dto;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void evictTestGenerationCache(String problemDescription, List<String> base64Images, int count) {
        String sourceFingerprint = sourceFingerprint(problemDescription, base64Images);
        String pipelineKey = pipelineCacheKey(sourceFingerprint, count);
        evictCache(pipelineKey, "pipeline");
        evictCache("problem_text_v2_" + generateHash(sourceFingerprint), "problem-text");

        String problemText = resolveProblemTextForCacheEviction(problemDescription, base64Images, sourceFingerprint);
        String normalizedProblemText = normalizeProblemText(problemText);
        String analysisKey = ANALYSIS_CACHE_PREFIX + generateHash(normalizedProblemText);
        Optional<String> cachedAnalysis = readCache(analysisKey, String.class, "ollama-analysis-evict-lookup");
        if (cachedAnalysis.isPresent()) {
            String geminiKey = GEMINI_CACHE_PREFIX + generateHash(
                    normalizedProblemText + "|" + cachedAnalysis.get() + "|count=" + count);
            evictCache(geminiKey, "gemini-artifacts");
        }
        evictCache(analysisKey, "ollama-analysis");
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

        String ocrKey = "problem_text_v2_" + generateHash(sourceFingerprint);
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

        String ocrKey = "problem_text_v2_" + generateHash(sourceFingerprint);
        Optional<String> cachedText = readCache(ocrKey, String.class, "problem-text-evict-lookup");
        return cachedText.orElse(problemDescription != null ? problemDescription : "");
    }

    private String resolveAnalysisJson(String normalizedProblemText) {
        String analysisKey = ANALYSIS_CACHE_PREFIX + generateHash(normalizedProblemText);
        Optional<String> cachedAnalysis = readCache(analysisKey, String.class, "ollama-analysis");
        if (cachedAnalysis.isPresent()) {
            return cachedAnalysis.get();
        }

        System.out.println("INFO: [AI Pipeline] Step 1 - Ollama local analysis...");
        String analysisJson = ollamaAnalysisService.analyzeProblem(normalizedProblemText);
        saveCache(analysisKey, analysisJson, "ollama-analysis");
        return analysisJson;
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
            spec.put("input_schema", dto.getInputSchema());
            spec.put("output_format", dto.getOutputFormat());
            spec.put("analysis_json", analysisJson == null ? "{}" : analysisJson);
            spec.put("test_plan", dto.getTestPlan());
            spec.put("bug_classes", dto.getBugClasses());
            spec.put("wrong_solutions", dto.getWrongSolutions());
            spec.put("bruteforce_solution", dto.getBruteForceSolution());
            spec.put("bruteforce_language", dto.getBruteForceLanguage());
            spec.put("validator_rules", dto.getValidatorRules());
            spec.put("size_profiles", Map.of(
                    "small", "tiny valid testcase, preferably N <= 10 when applicable",
                    "medium", "moderate valid testcase",
                    "large", "near upper constraints but must finish under 1 second",
                    "stress", "adversarial near max constraints, valid and deterministic"
            ));
            spec.put("generator_profiles", buildGeneratorProfiles(dto));
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

    private Map<String, String> buildGeneratorProfiles(AiResponseDTO dto) {
        Map<String, String> profiles = new LinkedHashMap<>();
        if (dto.getTestProfiles() != null) {
            for (AiResponseDTO.TestProfile profile : dto.getTestProfiles()) {
                if (profile == null || profile.getName() == null || profile.getName().isBlank()) continue;
                String objective = profile.getObjective();
                String difficulty = profile.getDifficulty();
                String runtimeName = normalizeGeneratorProfileName(profile.getName());
                profiles.put(runtimeName,
                        (objective == null || objective.isBlank() ? "targeted adversarial coverage" : objective)
                                + (difficulty == null || difficulty.isBlank() ? "" : " [" + difficulty + "]"));
            }
        }

        if (profiles.isEmpty()) {
            for (String name : defaultProfileNames(dto)) {
                profiles.put(name, defaultProfileDescription(name));
            }
        }
        return profiles;
    }

    private List<String> defaultProfileNames(AiResponseDTO dto) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add("edge_boundary");
        names.add("random_small");
        names.add("random_large");
        names.add("stress_performance");

        if (dto.getBugClasses() != null) {
            for (AiResponseDTO.BugClass bugClass : dto.getBugClasses()) {
                if (bugClass == null || bugClass.getName() == null) continue;
                String upper = bugClass.getName().toUpperCase(Locale.ROOT);
                if (upper.contains("OVERFLOW")) names.add("overflow_int32");
                if (upper.contains("INT64") || upper.contains("BIGINTEGER")) names.add("overflow_int64_if_relevant");
                if (upper.contains("GREEDY")) {
                    names.add("anti_greedy_small");
                    names.add("tie_breaking");
                }
            }
        }

        if (dto.getWrongSolutions() != null) {
            for (AiResponseDTO.ExecutableWrongSolution wrong : dto.getWrongSolutions()) {
                if (wrong == null || wrong.getType() == null) continue;
                String type = wrong.getType().toLowerCase(Locale.ROOT);
                if (type.contains("overflow")) names.add("overflow_int32");
                if (type.contains("greedy")) names.add("anti_greedy_small");
                if (type.contains("boundary")) names.add("edge_boundary");
            }
        }

        return new ArrayList<>(names);
    }

    private String defaultProfileDescription(String name) {
        return switch (name) {
            case "edge_boundary" -> "minimum/maximum valid values and boundary-sensitive structure";
            case "overflow_int32" -> "force accumulated values above 2^31-1 when relevant";
            case "overflow_int64_if_relevant" -> "force accumulated values near or above 2^63-1 when relevant";
            case "anti_greedy_small" -> "small counterexamples that defeat natural greedy choices";
            case "tie_breaking" -> "equal local choices where only one direction is globally correct";
            case "random_small" -> "tiny random cases suitable for brute-force cross-checking";
            case "random_large" -> "large randomized valid cases near realistic limits";
            case "stress_performance" -> "near-maximum performance traps for slow but correct approaches";
            case "adversarial_structure" -> "structured cases such as monotone, all-equal, alternating, sparse, or chain-like";
            default -> "targeted adversarial coverage";
        };
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
        return PIPELINE_CACHE_PREFIX + generateHash(sourceFingerprint + "|count=" + count);
    }
}
