package com.pbj.service;

import com.pbj.dto.AiResponseDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TestProfileNormalizationService {

    public List<AiResponseDTO.TestProfile> normalize(AiResponseDTO dto) {
        Map<String, AiResponseDTO.TestProfile> profiles = new LinkedHashMap<>();

        addRequiredProfile(profiles, "edge_boundary", "Cover minimum/maximum valid values and boundary-sensitive structure.", "edge", 2);
        addRequiredProfile(profiles, "random_small", "Tiny valid cases suitable for brute-force cross-checking.", "small", 4);
        addRequiredProfile(profiles, "medium", "Moderate valid cases between tiny and stress sizes.", "medium", 2);
        addRequiredProfile(profiles, "random_large", "Large valid cases near realistic upper bounds.", "large", 2);
        addRequiredProfile(profiles, "stress_performance", "Near-maximum cases for slow but otherwise correct solutions.", "stress", 2);

        if (dto != null && dto.getTestProfiles() != null) {
            for (AiResponseDTO.TestProfile suggested : dto.getTestProfiles()) {
                if (suggested == null) continue;
                String canonical = canonicalName(suggested.getName());
                if (canonical == null) continue;

                AiResponseDTO.TestProfile existing = profiles.get(canonical);
                if (existing == null) {
                    profiles.put(canonical, copyAsCanonical(suggested, canonical));
                } else {
                    mergeSuggestion(existing, suggested);
                }
            }
        }

        if (hasBugClass(dto, "overflow")) {
            addRequiredProfile(profiles, "overflow_int32",
                    "Force accumulated values above 32-bit range when the schema permits it.", "overflow", 2);
        }
        if (hasBugClass(dto, "greedy")) {
            addRequiredProfile(profiles, "anti_greedy_small",
                    "Small counterexamples that defeat locally attractive choices.", "adversarial", 2);
            addRequiredProfile(profiles, "tie_breaking",
                    "Equal local choices where only one global outcome is correct.", "adversarial", 2);
        }

        return new ArrayList<>(profiles.values());
    }

    private void addRequiredProfile(Map<String, AiResponseDTO.TestProfile> profiles,
                                    String name,
                                    String objective,
                                    String difficulty,
                                    int seedCount) {
        profiles.computeIfAbsent(name, ignored -> {
            AiResponseDTO.TestProfile profile = new AiResponseDTO.TestProfile();
            profile.setName(name);
            profile.setObjective(objective);
            profile.setDifficulty(difficulty);
            profile.setSeedCount(seedCount);
            profile.setRequired(true);
            return profile;
        });
    }

    private AiResponseDTO.TestProfile copyAsCanonical(AiResponseDTO.TestProfile suggested, String canonical) {
        AiResponseDTO.TestProfile profile = new AiResponseDTO.TestProfile();
        profile.setName(canonical);
        profile.setObjective(suggested.getObjective());
        profile.setDifficulty(suggested.getDifficulty());
        profile.setSeedCount(suggested.getSeedCount());
        profile.setTargetsWrongSolutions(suggested.getTargetsWrongSolutions());
        profile.setRequired(suggested.getRequired());
        return profile;
    }

    private void mergeSuggestion(AiResponseDTO.TestProfile target, AiResponseDTO.TestProfile suggested) {
        if (isBlank(target.getObjective()) && !isBlank(suggested.getObjective())) {
            target.setObjective(suggested.getObjective());
        }
        if (isBlank(target.getDifficulty()) && !isBlank(suggested.getDifficulty())) {
            target.setDifficulty(suggested.getDifficulty());
        }
        if (target.getSeedCount() == null && suggested.getSeedCount() != null) {
            target.setSeedCount(suggested.getSeedCount());
        }
        if ((target.getTargetsWrongSolutions() == null || target.getTargetsWrongSolutions().isEmpty())
                && suggested.getTargetsWrongSolutions() != null
                && !suggested.getTargetsWrongSolutions().isEmpty()) {
            target.setTargetsWrongSolutions(suggested.getTargetsWrongSolutions());
        }
        if (Boolean.TRUE.equals(suggested.getRequired())) {
            target.setRequired(true);
        }
    }

    private String canonicalName(String name) {
        if (isBlank(name)) return null;
        String normalized = name.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (normalized) {
            case "sample", "boundary_min", "boundary_max", "edge_boundary" -> "edge_boundary";
            case "small_exhaustive", "random_small" -> "random_small";
            case "random_medium", "medium" -> "medium";
            case "random_large" -> "random_large";
            case "stress", "stress_test", "stress_performance" -> "stress_performance";
            case "overflow", "overflow_int32" -> "overflow_int32";
            case "overflow_int64", "overflow_int64_if_relevant" -> "overflow_int64_if_relevant";
            case "anti_greedy_small", "adversarial_greedy" -> "anti_greedy_small";
            case "tie_breaking", "duplicate_values" -> "tie_breaking";
            case "adversarial_structure", "adversarial_graph_structure", "adversarial_sorting" -> "adversarial_structure";
            default -> null;
        };
    }

    private boolean hasBugClass(AiResponseDTO dto, String token) {
        if (dto == null || dto.getBugClasses() == null) return false;
        for (AiResponseDTO.BugClass bugClass : dto.getBugClasses()) {
            if (bugClass == null || isBlank(bugClass.getName())) continue;
            if (bugClass.getName().toLowerCase(Locale.ROOT).contains(token)) return true;
        }
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
