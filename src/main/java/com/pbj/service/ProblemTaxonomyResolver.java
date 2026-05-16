package com.pbj.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.pbj.dto.AiResponseDTO;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class ProblemTaxonomyResolver {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}");

    public ProblemMetadata resolve(AiResponseDTO dto, String sourceStatement) {
        String statement = sourceStatement == null || sourceStatement.isBlank()
                ? safe(dto == null ? null : dto.getFormattedDescription())
                : sourceStatement;
        String inputFormat = safe(dto == null ? null : dto.getInputFormat());
        String outputFormat = safe(dto == null ? null : dto.getOutputFormat());
        String constraints = safe(dto == null ? null : dto.getConstraints());
        JsonNode schema = dto == null ? null : dto.getInputSchema();

        String text = normalize(String.join(" ", statement, inputFormat, outputFormat, constraints,
                dto != null && dto.getTestPlan() != null ? safe(dto.getTestPlan().getProblemType()) : "",
                dto != null && dto.getTestPlan() != null ? safe(dto.getTestPlan().getIntendedSolution()) : ""));

        ProblemType type = resolveType(schema, text);
        return new ProblemMetadata(type, schema, statement, inputFormat, outputFormat, constraints);
    }

    private ProblemType resolveType(JsonNode schema, String text) {
        if (looksLikeAlternatingEdgeShortestPath(schema, text)) {
            return ProblemType.GRAPH_ALTERNATING_EDGE_SHORTEST_PATH;
        }
        if (hasEdgeLine(schema) && containsAny(text, "shortest path", "duong di ngan nhat", "dijkstra")) {
            return ProblemType.GRAPH_SHORTEST_PATH;
        }
        if (containsAny(text, "dag", "khong chu trinh") && containsAny(text, "dp", "quy hoach dong")) {
            return ProblemType.DAG_DP;
        }
        if (containsAny(text, "tree", "cay")) {
            return ProblemType.TREE_DP;
        }
        if (containsAny(text, "grid", "luoi", "ma tran") && containsAny(text, "bfs", "duong di")) {
            return ProblemType.GRID_BFS;
        }
        if (containsAny(text, "prefix sum", "tong tien to")) {
            return ProblemType.ARRAY_PREFIX_SUM;
        }
        if (containsAny(text, "two pointer", "hai con tro", "sap xep va hai")) {
            return ProblemType.ARRAY_TWO_POINTERS;
        }
        if (schema != null && schema.path("lines").isArray()) {
            return ProblemType.GENERIC_SCHEMA;
        }
        return ProblemType.UNKNOWN;
    }

    private boolean looksLikeAlternatingEdgeShortestPath(JsonNode schema, String text) {
        if (!hasTypedWeightedEdgeLine(schema)) return false;
        return containsAny(text, "luan phien", "alternating", "am duong", "am - duong", "type")
                && containsAny(text, "ngan nhat", "shortest", "min cost", "thoi gian");
    }

    private boolean hasEdgeLine(JsonNode schema) {
        if (schema == null || !schema.path("lines").isArray()) return false;
        for (JsonNode line : schema.path("lines")) {
            if ("edges".equalsIgnoreCase(line.path("kind").asText(""))) return true;
        }
        return false;
    }

    private boolean hasTypedWeightedEdgeLine(JsonNode schema) {
        if (schema == null || !schema.path("lines").isArray()) return false;
        for (JsonNode line : schema.path("lines")) {
            if (!"edges".equalsIgnoreCase(line.path("kind").asText(""))) continue;
            JsonNode columns = line.path("columns");
            if (!columns.isArray() || columns.size() < 4) continue;

            boolean hasWeight = false;
            boolean hasType = false;
            int nodeColumns = 0;
            for (JsonNode column : columns) {
                String name = column.path("name").asText("").toLowerCase(Locale.ROOT);
                String type = column.path("type").asText("").toLowerCase(Locale.ROOT);
                if ("node".equals(type) || "vertex".equals(type)) nodeColumns++;
                if (name.equals("w") || name.equals("weight") || name.equals("cost")) hasWeight = true;
                if (name.equals("type") || name.equals("t")) {
                    long min = column.path("min").asLong(Long.MIN_VALUE);
                    long max = column.path("max").asLong(Long.MAX_VALUE);
                    hasType = min == 0L && max == 1L;
                }
            }
            if (nodeColumns >= 2 && hasWeight && hasType) return true;
        }
        return false;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    private String normalize(String input) {
        String decomposed = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFD);
        return DIACRITICS.matcher(decomposed)
                .replaceAll("")
                .replace('Đ', 'D')
                .replace('đ', 'd')
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\- ]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
