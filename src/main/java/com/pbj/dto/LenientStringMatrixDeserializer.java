package com.pbj.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class LenientStringMatrixDeserializer extends JsonDeserializer<List<List<String>>> {
    @Override
    public List<List<String>> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);
        List<List<String>> rows = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return rows;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                rows.add(toRow(child));
            }
            return rows;
        }
        rows.add(toRow(node));
        return rows;
    }

    private List<String> toRow(JsonNode node) {
        List<String> row = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return row;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (!child.isNull() && !child.isMissingNode()) {
                    String value = child.isObject() ? child.toString() : child.asText("");
                    if (!value.isBlank()) {
                        row.add(value);
                    }
                }
            }
            return row;
        }
        String value = node.isObject() ? node.toString() : node.asText("");
        if (!value.isBlank()) {
            row.add(value);
        }
        return row;
    }
}
