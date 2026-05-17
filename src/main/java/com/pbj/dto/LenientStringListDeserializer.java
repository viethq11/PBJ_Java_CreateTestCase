package com.pbj.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class LenientStringListDeserializer extends JsonDeserializer<List<String>> {
    @Override
    public List<String> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);
        List<String> values = new ArrayList<>();
        collect(node, values);
        return values;
    }

    private void collect(JsonNode node, List<String> values) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collect(child, values);
            }
            return;
        }
        if (node.isObject()) {
            values.add(node.toString());
            return;
        }
        String value = node.asText("");
        if (!value.isBlank()) {
            values.add(value);
        }
    }
}
