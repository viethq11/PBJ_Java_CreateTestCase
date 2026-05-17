package com.pbj.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

import java.util.List;

@Data
public class SemanticSpecDTO {
    @JsonProperty("query_variables")
    @JsonDeserialize(using = LenientStringListDeserializer.class)
    private List<String> queryVariables;

    @JsonProperty("ignored_variables")
    @JsonDeserialize(using = LenientStringListDeserializer.class)
    private List<String> ignoredVariables;

    @JsonDeserialize(using = LenientStringMatrixDeserializer.class)
    private List<List<String>> paths;

    @JsonDeserialize(using = LenientStringListDeserializer.class)
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
    @JsonDeserialize(using = LenientStringListDeserializer.class)
    private List<String> countedObjects;

    @JsonProperty("output_semantics")
    private String outputSemantics;
}
