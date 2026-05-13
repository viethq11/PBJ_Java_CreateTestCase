package com.pbj.service;

import com.pbj.dto.AiResponseDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProblemServiceQualityTest {

    private final ProblemService service = new ProblemService(
            null, null, null, null, null, null, null, null, null, null, null, null);

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

    @Test
    @SuppressWarnings("unchecked")
    void overflowRiskAddsBroaderMiningProfiles() throws Exception {
        AiResponseDTO dto = new AiResponseDTO();
        dto.setWrongSolutions(List.of(probe("overflow_probe", "overflow")));

        Method method = ProblemService.class.getDeclaredMethod("buildProbeMiningProfiles", AiResponseDTO.class);
        method.setAccessible(true);
        Map<String, Integer> profiles = (Map<String, Integer>) method.invoke(service, dto);

        assertThat(profiles).containsKeys(
                "overflow_int32",
                "overflow_int64_if_relevant",
                "random_large",
                "stress_performance"
        );
    }

    @Test
    void generatorProbeProfilesCoverRuntimeProfiles() throws Exception {
        Method method = ProblemService.class.getDeclaredMethod("buildGeneratorProbeProfiles");
        method.setAccessible(true);
        String[] profiles = (String[]) method.invoke(service);

        assertThat(profiles).contains(
                "edge_boundary",
                "random_small",
                "random_large",
                "overflow_int64_if_relevant",
                "adversarial_structure",
                "stress_performance"
        );
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
