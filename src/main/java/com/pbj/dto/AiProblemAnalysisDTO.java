package com.pbj.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import java.util.List;

@Data
public class AiProblemAnalysisDTO {
    @JsonProperty("problem_type")
    private String problemType;

    @JsonProperty("algorithm_family")
    private String algorithmFamily;

    @JsonProperty("input_pattern")
    private String inputPattern;

    @JsonProperty("constraints")
    private String constraints;

    @JsonProperty("input_model")
    private JsonNode inputModel;

    @JsonProperty("template_id")
    private String templateId;

    @JsonProperty("risk_tags")
    private List<String> riskTags;

    @JsonProperty("analysis_summary")
    private String analysisSummary;
}
