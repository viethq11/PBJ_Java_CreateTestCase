package com.pbj.service;

import com.pbj.dto.AiResponseDTO;
import com.pbj.entity.Problem;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProblemServiceQualityTest {

    private final ProblemService service = new ProblemService(
            null, null, null, null, null, null, null, null, null, null, null, null, null);

    @Test
    void coverageGateReportCollectsKeySignalsWithoutScoring() {
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

        ProblemService.CoverageGateReport report = service.buildCoverageGateReport(dto, quality);

        assertThat(report.signals()).contains(
                "boundary",
                "small_or_exhaustive",
                "large_or_stress",
                "bug_oriented_adversarial",
                "bruteforce_verified",
                "kills_overflow_probe",
                "kills_greedy_probe",
                "kills_boundary_probe",
                "mined_probe_killers"
        );
    }

    @Test
    void coverageGateRejectsSurvivingOverflowProbe() {
        AiResponseDTO dto = new AiResponseDTO();
        dto.setWrongSolutions(List.of(probe("overflow_probe", "overflow")));

        ProblemService.GenerationQualitySummary quality = new ProblemService.GenerationQualitySummary();
        quality.acceptedProfiles.add("edge_boundary");
        quality.acceptedProfiles.add("random_small");
        quality.acceptedProfiles.add("random_large");
        quality.acceptedProfiles.add("stress_performance");
        quality.withKilledBySuite(Set.of());

        assertThatThrownBy(() -> service.validateCoverageGates(dto, quality))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overflow probe survived");
    }

    @Test
    void coverageGateAllowsBaselineProfilesWhenNoOverflowOrGreedyRisk() {
        AiResponseDTO dto = new AiResponseDTO();

        ProblemService.GenerationQualitySummary quality = new ProblemService.GenerationQualitySummary();
        quality.acceptedProfiles.add("edge_boundary");
        quality.acceptedProfiles.add("random_small");
        quality.acceptedProfiles.add("stress_performance");
        quality.hasBruteForceArtifact = true;
        quality.bruteForceVerifiedCases = 2;
        quality.withKilledBySuite(Set.of());

        service.validateCoverageGates(dto, quality);
    }

    @Test
    void coverageGateRejectsMissingAdversarialProfile() {
        AiResponseDTO dto = new AiResponseDTO();

        ProblemService.GenerationQualitySummary quality = new ProblemService.GenerationQualitySummary();
        quality.acceptedProfiles.add("edge_boundary");
        quality.acceptedProfiles.add("random_small");
        quality.acceptedProfiles.add("random_large");
        quality.withKilledBySuite(Set.of());

        assertThatThrownBy(() -> service.validateCoverageGates(dto, quality))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("adversarial");
    }

    @Test
    void coverageGateAllowsMediumAndAdversarialWhenLargeOracleTimedOut() {
        AiResponseDTO dto = new AiResponseDTO();

        ProblemService.GenerationQualitySummary quality = new ProblemService.GenerationQualitySummary();
        quality.acceptedProfiles.add("edge_boundary");
        quality.acceptedProfiles.add("random_small");
        quality.acceptedProfiles.add("medium");
        quality.acceptedProfiles.add("anti_greedy_small");
        quality.withKilledBySuite(Set.of());

        service.validateCoverageGates(dto, quality);
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
                "stress_performance"
        );
        assertThat(profiles).hasSize(7);
    }

    @Test
    void resolveGoldenSolutionPrefersStoredAcceptedSolutionOverAiArtifact() throws Exception {
        Problem problem = new Problem();
        problem.setAcceptedSolutionCode("#include <bits/stdc++.h>\nint main(){return 0;}");

        AiResponseDTO dto = new AiResponseDTO();
        dto.setGoldenSolution("#include <bits/stdc++.h>\nint main(){return 1;}");

        Method method = ProblemService.class.getDeclaredMethod("resolveGoldenSolution", Problem.class, AiResponseDTO.class);
        method.setAccessible(true);
        String resolved = (String) method.invoke(service, problem, dto);

        assertThat(resolved).isEqualTo(problem.getAcceptedSolutionCode());
    }

    @Test
    void formalSpecFailureBecomesActionableNeedsInputFeedback() {
        GenerationNeedsInputException ex = service.needsInputForFormalSpec(
                new IllegalStateException("Formal spec validation failed"));

        assertThat(ex.getFeedback().getStage()).isEqualTo("formal_spec");
        assertThat(ex.getFeedback().getCompletedStages()).contains("Trích xuất ngữ nghĩa");
        assertThat(ex.getFeedback().getMissingInformation()).contains("Định dạng input đầy đủ");
    }

    @Test
    void groundingFailureBecomesActionableNeedsInputFeedback() {
        GenerationNeedsInputException ex = service.needsInputForGrounding(
                new IllegalStateException("Formal spec grounding failed"));

        assertThat(ex.getFeedback().getStage()).isEqualTo("source_grounding");
        assertThat(ex.getFeedback().getClarification()).contains("OCR");
        assertThat(ex.getFeedback().getMissingInformation()).contains("Ví dụ input/output hoặc mô tả truy vấn");
    }

    @Test
    void missingCompilerArtifactsAreClassifiedAsInternalWorkNotUserInput() {
        GenerationNeedsInputException ex = service.needsInternalCompilerRepair(
                new IllegalStateException("test_plan is missing. input_schema is missing."));

        assertThat(ex.getFeedback().getStage()).isEqualTo("artifact_compilation");
        assertThat(ex.getFeedback().getSummary()).contains("đã đọc đủ đề");
        assertThat(ex.getFeedback().getClarification()).contains("người dùng không cần nhập lại đề");
    }

    @Test
    void geminiQuotaFailureBecomesActionableRecoveryFeedback() {
        GenerationNeedsInputException ex = service.needsGeminiQuotaRecovery(
                new GeminiQuotaExceededException("HTTP 429 RESOURCE_EXHAUSTED"));

        assertThat(ex.getFeedback().getStage()).isEqualTo("gemini_quota");
        assertThat(ex.getFeedback().getSummary()).contains("tránh spam request");
        assertThat(ex.getFeedback().getClarification()).contains("GEMINI_API_KEYS");
        assertThat(ex.getFeedback().getMissingInformation()).contains("Quota Gemini khả dụng");
    }

    @Test
    void cppValidatorIsNotAcceptedAsRunnablePythonValidator() throws Exception {
        Method method = ProblemService.class.getDeclaredMethod("isUsablePythonValidator", String.class);
        method.setAccessible(true);

        assertThat((boolean) method.invoke(service, """
                #include <bits/stdc++.h>
                using namespace std;
                int main(){ return 0; }
                """)).isFalse();
        assertThat((boolean) method.invoke(service, """
                import sys
                def validate():
                    return sys.stdin.read()
                """)).isTrue();
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

    @Test
    void generateAndSaveProblemFromBase64FallsBackToLegacyWhenV2ReturnsNull() {
        com.pbj.v2.generation.V2ProblemGenerationService v2Mock = org.mockito.Mockito.mock(com.pbj.v2.generation.V2ProblemGenerationService.class);
        org.mockito.Mockito.when(v2Mock.generate(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.anyList()))
                .thenReturn(null);

        ProblemService serviceWithMock = new ProblemService(
                null, null, null, null, null, null, null, null, null, null, v2Mock, null, null);

        assertThatThrownBy(() -> serviceWithMock.generateAndSaveProblemFromBase64("title", "description", List.of(), false))
                .isInstanceOf(NullPointerException.class);
    }
}
