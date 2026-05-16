package com.pbj.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pbj.dto.AiResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VerificationService {
    private final CodeExecutionService codeExecutionService;
    private final ObjectMapper objectMapper;

    public static class VerificationReport {
        public boolean passed;
        public String message;
        public List<CheckResult> results = new ArrayList<>();
    }

    public static class CheckResult {
        public String testcaseName;
        public boolean match;
        public String bruteOutput;
        public String goldenOutput;
        public String message;
    }

    public VerificationReport verifyBruteVsGolden(AiResponseDTO dto, int smallTestsCount) {
        VerificationReport report = new VerificationReport();
        report.passed = true;

        if (dto.getBruteForceSolution() == null || dto.getBruteForceSolution().isBlank()) {
            report.passed = false;
            report.message = "Missing brute force solution";
            return report;
        }
        if (dto.getGoldenSolution() == null || dto.getGoldenSolution().isBlank()) {
            report.passed = false;
            report.message = "Missing golden solution";
            return report;
        }

        String generatorCode = dto.getGeneratorCode();
        String generatorLang = dto.getGeneratorLanguage();
        String bruteCode = dto.getBruteForceSolution();
        String bruteLang = dto.getBruteForceLanguage();
        String goldenCode = dto.getGoldenSolution();
        String goldenLang = "cpp"; // golden is always cpp in this system

        for (int i = 1; i <= smallTestsCount; i++) {
            String input = codeExecutionService.runGenerator(generatorCode, generatorLang, i, "random_small");
            if (input == null) {
                report.passed = false;
                report.message = "Generator failed on test " + i;
                return report;
            }

            String bruteOutput = codeExecutionService.runGoldenSolution(bruteCode, bruteLang, input, 5000);
            String goldenOutput = codeExecutionService.runGoldenSolution(goldenCode, goldenLang, input, 5000);

            CheckResult result = new CheckResult();
            result.testcaseName = "small_random_" + i;
            result.bruteOutput = bruteOutput;
            result.goldenOutput = goldenOutput;

            if (bruteOutput == null || goldenOutput == null) {
                result.match = false;
                result.message = "Execution failed for " + (bruteOutput == null ? "brute" : "golden");
                report.passed = false;
            } else if (!compareOutputs(bruteOutput, goldenOutput)) {
                result.match = false;
                result.message = "Output mismatch";
                report.passed = false;
            } else {
                result.match = true;
                result.message = "Match";
            }
            report.results.add(result);
            
            if (!report.passed && i >= 3) break; // Fail early if at least 3 tests done
        }

        report.message = report.passed ? "Verified successfully" : "Verification failed";
        return report;
    }

    private boolean compareOutputs(String out1, String out2) {
        if (out1 == null || out2 == null) return false;
        String[] tokens1 = out1.trim().split("\\s+");
        String[] tokens2 = out2.trim().split("\\s+");
        if (tokens1.length != tokens2.length) return false;
        for (int i = 0; i < tokens1.length; i++) {
            if (!tokens1[i].equals(tokens2[i])) return false;
        }
        return true;
    }

    public JsonNode reportToJson(VerificationReport report) {
        return objectMapper.valueToTree(report);
    }
}
