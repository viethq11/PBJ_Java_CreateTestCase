package com.pbj.service;

import com.fasterxml.jackson.databind.JsonNode;

public record ProblemMetadata(
        ProblemType type,
        JsonNode inputSchema,
        String statement,
        String inputFormat,
        String outputFormat,
        String constraints
) {
    public boolean hasType(ProblemType expected) {
        return type == expected;
    }
}
