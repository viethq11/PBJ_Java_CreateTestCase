package com.pbj.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

@Data
public class AiResponseDTO {

    private String understanding;

    @JsonProperty("formatted_description")
    private String formattedDescription;

    @JsonProperty("input_format")
    private String inputFormat;

    @JsonProperty("output_format")
    private String outputFormat;

    private String constraints;

    @JsonProperty("input_schema")
    private JsonNode inputSchema;

    @JsonProperty("total_testcases")
    private Integer totalTestcases;

    /** Code Python or C++ để sinh input data */
    @JsonProperty("generator_code")
    private String generatorCode;

    /** Code C++ lời giải chuẩn (Golden Solution) */
    @JsonProperty("golden_solution")
    private String goldenSolution;

    /** Ngôn ngữ của generator_code: "python" hoặc "cpp" */
    @JsonProperty("generator_language")
    private String generatorLanguage;

    /** Các rule validate input/output */
    @JsonProperty("validator_rules")
    private List<String> validatorRules;

    /** Chiến lược sinh testcase */
    @JsonProperty("generation_strategy")
    private GenerationStrategy generationStrategy;

    /** Edge cases thủ công (N <= 20) để bổ sung vào bộ test */
    @JsonProperty("edge_cases")
    private List<TestCaseDTO> edgeCases;

    @JsonProperty("checker_code")
    private String checkerCode;

    @JsonProperty("validator_code")
    private String validatorCode;

    @JsonProperty("test_plan")
    private TestPlan testPlan;

    @Data
    public static class TestPlan {
        @JsonProperty("problem_type")
        private String problemType;

        @JsonProperty("intended_solution")
        private String intendedSolution;

        @JsonProperty("wrong_solutions")
        private List<WrongSolution> wrongSolutions;

        @JsonProperty("test_families")
        private List<TestFamily> testFamilies;

        @JsonProperty("generator_requirements")
        private GeneratorRequirements generatorRequirements;
    }

    @Data
    public static class WrongSolution {
        private String name;

        @JsonProperty("why_wrong")
        private String whyWrong;

        @JsonProperty("counterexample_strategy")
        private String counterexampleStrategy;
    }

    @Data
    public static class TestFamily {
        private String name;

        private String difficulty;

        private List<String> target;

        private String constraints;

        private String expected;

        private String reason;
    }

    @Data
    public static class GeneratorRequirements {
        @JsonProperty("must_include_bruteforce_for_small")
        private boolean mustIncludeBruteforceForSmall;

        @JsonProperty("must_include_large_stress")
        private boolean mustIncludeLargeStress;

        @JsonProperty("must_include_complexity_traps")
        private boolean mustIncludeComplexityTraps;

        @JsonProperty("must_include_numeric_extremes")
        private boolean mustIncludeNumericExtremes;

        @JsonProperty("must_avoid_raw_large_data")
        private boolean mustAvoidRawLargeData;
    }

    @Data
    public static class GenerationStrategy {
        @JsonProperty("small_cases")
        private boolean smallCases;

        @JsonProperty("random_cases")
        private boolean randomCases;

        @JsonProperty("edge_cases")
        private boolean edgeCases;

        @JsonProperty("stress_cases")
        private boolean stressCases;
    }

    /** Dùng cho edge_cases thủ công (N nhỏ) */
    @Data
    public static class TestCaseDTO {
        private String input;

        @JsonProperty("expected_output")
        private String expectedOutput;

        @JsonProperty("is_sample")
        private Boolean isSample;
    }
}
