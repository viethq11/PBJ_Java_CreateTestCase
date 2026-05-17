package com.pbj.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

@Data
public class SemanticSpecDTO {
    @JsonProperty("query_variables")
    private List<String> queryVariables;

    @JsonProperty("ignored_variables")
    private List<String> ignoredVariables;

    private List<List<String>> paths;

    private List<String> conditions;

    @JsonProperty("graph_type")
    private String graphType;

    @JsonProperty("value_domain")
    private JsonNode valueDomain;

    @JsonProperty("input_model")
    private JsonNode inputModel;

    @JsonProperty("constraints")
    private JsonNode constraints;

    @JsonProperty("counted_objects")
    private List<String> countedObjects;

    @JsonProperty("output_semantics")
    private String outputSemantics;
}
