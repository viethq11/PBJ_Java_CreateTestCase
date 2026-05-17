package com.pbj.service;

import com.pbj.dto.AiResponseDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestProfileNormalizationServiceTest {
    private final TestProfileNormalizationService service = new TestProfileNormalizationService();

    @Test
    void addsDeterministicBaselineProfilesAndCanonicalizesAiSuggestions() {
        AiResponseDTO dto = new AiResponseDTO();
        AiResponseDTO.TestProfile suggested = new AiResponseDTO.TestProfile();
        suggested.setName("random medium");
        suggested.setObjective("AI hint");
        dto.setTestProfiles(List.of(suggested));

        List<AiResponseDTO.TestProfile> profiles = service.normalize(dto);

        assertThat(profiles).extracting(AiResponseDTO.TestProfile::getName)
                .containsExactly(
                        "edge_boundary",
                        "random_small",
                        "medium",
                        "random_large",
                        "stress_performance");
        assertThat(profiles.get(2).getObjective()).isEqualTo("Moderate valid cases between tiny and stress sizes.");
    }

    @Test
    void derivesAdversarialProfilesFromBugClasses() {
        AiResponseDTO dto = new AiResponseDTO();
        AiResponseDTO.BugClass overflow = new AiResponseDTO.BugClass();
        overflow.setName("Integer Overflow");
        AiResponseDTO.BugClass greedy = new AiResponseDTO.BugClass();
        greedy.setName("Greedy trap");
        dto.setBugClasses(List.of(overflow, greedy));

        assertThat(service.normalize(dto)).extracting(AiResponseDTO.TestProfile::getName)
                .contains("overflow_int32", "anti_greedy_small", "tie_breaking");
    }
}
