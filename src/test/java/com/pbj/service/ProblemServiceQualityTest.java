package com.pbj.service;

import com.pbj.dto.AiResponseDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProblemServiceQualityTest {

    private final ProblemService service = new ProblemService(
            null, null, null, null, null, null, null, null, null, null);

    @Test
    void qualityReportScoresKeySignals() {
        AiResponseDTO dto = new AiResponseDTO();
        dto.setWrongSolutions(List.of(
                probe("overflow_probe", "overflow"),
                probe("greedy_probe", "greedy"),
                probe("boundary_probe", "boundary")
        ));

        ProblemService.GenerationQualitySummary quality = new ProblemService.GenerationQualitySummary();
        quality.acceptedProfiles.add("edge_boundary");
        quality.acceptedProfiles.add("stress_performance");
        quality.hasBruteForceArtifact = true;
        quality.bruteForceVerifiedCases = 3;
        quality.minedProbeKillerCases = 2;
        quality.withKilledBySuite(Set.of("overflow_probe", "greedy_probe", "boundary_probe"));

        ProblemService.QualityReport report = service.buildQualityReport(dto, quality);

        assertThat(report.score()).isGreaterThanOrEqualTo(6);
        assertThat(report.signals()).contains(
                "boundary",
                "bruteforce_verified",
                "kills_overflow_probe",
                "kills_greedy_probe",
                "kills_boundary_probe",
                "mined_probe_killers"
        );
    }

    @Test
    void qualityGateRejectsSurvivingOverflowProbe() {
        AiResponseDTO dto = new AiResponseDTO();
        dto.setWrongSolutions(List.of(probe("overflow_probe", "overflow")));

        ProblemService.GenerationQualitySummary quality = new ProblemService.GenerationQualitySummary();
        quality.acceptedProfiles.add("edge_boundary");
        quality.acceptedProfiles.add("random_large");
        quality.withKilledBySuite(Set.of());

        assertThatThrownBy(() -> service.validateQualityGate(dto, quality))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overflow probe survived");
    }

    @Test
    void qualityGateAllowsBaselineScoreWhenNoOverflowOrGreedyRisk() {
        AiResponseDTO dto = new AiResponseDTO();

        ProblemService.GenerationQualitySummary quality = new ProblemService.GenerationQualitySummary();
        quality.acceptedProfiles.add("edge_boundary");
        quality.acceptedProfiles.add("stress_performance");
        quality.hasBruteForceArtifact = true;
        quality.bruteForceVerifiedCases = 2;
        quality.withKilledBySuite(Set.of());

        service.validateQualityGate(dto, quality);
    }

    private AiResponseDTO.ExecutableWrongSolution probe(String name, String type) {
        AiResponseDTO.ExecutableWrongSolution probe = new AiResponseDTO.ExecutableWrongSolution();
        probe.setName(name);
        probe.setType(type);
        probe.setCode("int main(){return 0;}");
        probe.setLanguage("cpp");
        probe.setExpectedToFail(true);
        return probe;
    }
}
