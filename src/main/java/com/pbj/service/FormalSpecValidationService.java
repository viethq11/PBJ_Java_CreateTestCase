package com.pbj.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.pbj.dto.AiResponseDTO;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class FormalSpecValidationService {

    private static final Set<String> SUPPORTED_LINE_KINDS = Set.of(
            "scalars", "array", "matrix", "grid", "edges", "queries", "string", "raw_lines");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}");
    private static final Set<String> STOP_WORDS = Set.of(
            "mot", "hai", "ba", "bon", "cac", "cho", "cua", "voi", "trong", "ngoai", "dau",
            "dong", "tien", "tiep", "theo", "moi", "chua", "so", "nguyen", "duong", "lieu",
            "vao", "ra", "in", "neu", "thi", "la", "va", "hoac", "tu", "den", "tren", "duoi",
            "ban", "can", "hay", "duoc", "khong", "co", "gom", "nhat", "gia", "tri", "tim");

    public void validateForGeneration(AiResponseDTO dto) {
        List<String> errors = new ArrayList<>();

        if (dto == null) {
            throw new IllegalStateException("Formal spec validation failed: AI response is empty.");
        }

        requireText(errors, "input_format", dto.getInputFormat());
        requireText(errors, "output_format", dto.getOutputFormat());
        requireText(errors, "constraints", dto.getConstraints());
        if (dto.getTestPlan() == null) {
            errors.add("test_plan is missing.");
        } else {
            requireText(errors, "test_plan.problem_type", dto.getTestPlan().getProblemType());
            requireText(errors, "test_plan.intended_solution", dto.getTestPlan().getIntendedSolution());
            if (dto.getTestPlan().getTestFamilies() == null || dto.getTestPlan().getTestFamilies().isEmpty()) {
                errors.add("test_plan.test_families must include at least one family.");
            }
        }

        validateNoUnknown(errors, "input_format", dto.getInputFormat());
        validateNoUnknown(errors, "output_format", dto.getOutputFormat());
        validateNoUnknown(errors, "constraints", dto.getConstraints());
        validateInputSchema(errors, dto.getInputSchema());

        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Formal spec validation failed. Repair the problem specification before generating artifacts: "
                            + String.join(" ", errors));
        }
    }

    public void validateAgainstSource(String sourceProblemText, AiResponseDTO dto) {
        if (sourceProblemText == null || sourceProblemText.isBlank() || dto == null) return;

        List<String> errors = new ArrayList<>();
        String source = normalizeForComparison(sourceProblemText);
        String generatedText = String.join(" ",
                safe(dto.getFormattedDescription()),
                safe(dto.getUnderstanding()),
                safe(dto.getInputFormat()),
                safe(dto.getOutputFormat()),
                safe(dto.getConstraints()),
                dto.getTestPlan() == null ? "" : safe(dto.getTestPlan().getProblemType()),
                dto.getTestPlan() == null ? "" : safe(dto.getTestPlan().getIntendedSolution()));
        String generated = normalizeForComparison(generatedText);

        Set<String> sourceTokens = significantTokens(source);
        Set<String> generatedTokens = significantTokens(generated);
        if (sourceTokens.size() >= 8 && generatedTokens.size() >= 8) {
            Set<String> overlap = new HashSet<>(generatedTokens);
            overlap.retainAll(sourceTokens);
            double overlapRatio = overlap.size() / (double) generatedTokens.size();
            if (overlapRatio < 0.22d && !hasStableSchemaGrounding(source, dto.getInputSchema())) {
                errors.add("generated specification is weakly grounded in the original statement"
                        + " (token overlap " + Math.round(overlapRatio * 100) + "%).");
            }
        }

        validateGraphTupleShape(errors, source, dto.getInputSchema());
        validatePhantomArrayProblem(errors, source, dto.getInputSchema());

        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Formal spec grounding failed. The AI response appears to describe a different problem: "
                            + String.join(" ", errors));
        }
    }

    private void validateInputSchema(List<String> errors, JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            errors.add("input_schema is missing.");
            return;
        }

        JsonNode lines = schema.path("lines");
        if (!lines.isArray() || lines.isEmpty()) {
            errors.add("input_schema.lines must be a non-empty array.");
            return;
        }

        Set<String> definedScalars = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            JsonNode line = lines.get(i);
            String kind = line.path("kind").asText("").toLowerCase(Locale.ROOT);
            if (!SUPPORTED_LINE_KINDS.contains(kind)) {
                errors.add("input_schema.lines[" + i + "] has unsupported kind '" + kind + "'.");
                continue;
            }

            if ("scalars".equals(kind)) {
                validateScalarLine(errors, line, i, definedScalars);
            } else if ("array".equals(kind)) {
                validateLengthReference(errors, line.path("length"), definedScalars, "input_schema.lines[" + i + "].length");
                validateNumericBounds(errors, line, "input_schema.lines[" + i + "]");
            } else if ("matrix".equals(kind) || "grid".equals(kind)) {
                validateLengthReference(errors, firstPresent(line, "rows", "height", "n"), definedScalars,
                        "input_schema.lines[" + i + "].rows");
                validateLengthReference(errors, firstPresent(line, "cols", "columns", "width", "m"), definedScalars,
                        "input_schema.lines[" + i + "].cols");
                validateNumericBounds(errors, line, "input_schema.lines[" + i + "]");
            } else if ("edges".equals(kind) || "queries".equals(kind)) {
                validateLengthReference(errors, line.path("length"), definedScalars, "input_schema.lines[" + i + "].length");
                validateTupleColumns(errors, line.path("columns"), i, definedScalars);
            } else if ("string".equals(kind)) {
                validateLengthReference(errors, line.path("length"), definedScalars, "input_schema.lines[" + i + "].length");
            }
        }
    }

    private void validateGraphTupleShape(List<String> errors, String normalizedSource, JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) return;
        JsonNode lines = schema.path("lines");
        if (!lines.isArray()) return;

        boolean sourceMentionsFourEdgeColumns = normalizedSource.contains("4 so")
                || normalizedSource.contains("bon so")
                || normalizedSource.contains("u v w type")
                || normalizedSource.contains("u v trong so type")
                || (normalizedSource.contains("type") && normalizedSource.contains("w"));
        if (!sourceMentionsFourEdgeColumns) return;

        for (JsonNode line : lines) {
            String kind = line.path("kind").asText("").toLowerCase(Locale.ROOT);
            if (!"edges".equals(kind)) continue;
            int columnCount = line.path("columns").isArray() ? line.path("columns").size() : 0;
            if (columnCount != 4) {
                errors.add("source statement describes 4 values per edge, but input_schema edge tuple has "
                        + columnCount + " columns.");
            }
        }
    }

    private void validatePhantomArrayProblem(List<String> errors, String normalizedSource, JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) return;
        if (normalizedSource.contains("a[i]") || normalizedSource.contains("a i")
                || normalizedSource.contains("mang a") || normalizedSource.contains("day a")
                || normalizedSource.contains("array a") || normalizedSource.contains("sequence a")) {
            return;
        }

        JsonNode lines = schema.path("lines");
        if (!lines.isArray()) return;
        for (JsonNode line : lines) {
            String kind = line.path("kind").asText("").toLowerCase(Locale.ROOT);
            String name = line.path("name").asText("").toLowerCase(Locale.ROOT);
            if ("array".equals(kind) && "a".equals(name)) {
                errors.add("input_schema introduces array A even though the source statement does not.");
                return;
            }
        }
    }

    private void validateScalarLine(List<String> errors, JsonNode line, int lineIndex, Set<String> definedScalars) {
        JsonNode fields = line.path("fields");
        if (!fields.isArray() || fields.isEmpty()) {
            errors.add("input_schema.lines[" + lineIndex + "].fields must be a non-empty array.");
            return;
        }

        for (int f = 0; f < fields.size(); f++) {
            JsonNode field = fields.get(f);
            String path = "input_schema.lines[" + lineIndex + "].fields[" + f + "]";
            String name = field.path("name").asText("");
            if (!isIdentifier(name)) {
                errors.add(path + ".name must be a valid identifier.");
                continue;
            }
            validateNumericBounds(errors, field, path);
            definedScalars.add(name);
            definedScalars.add(name.toLowerCase(Locale.ROOT));
        }
    }

    private void validateTupleColumns(List<String> errors, JsonNode columns, int lineIndex, Set<String> definedScalars) {
        if (!columns.isArray() || columns.isEmpty()) {
            errors.add("input_schema.lines[" + lineIndex + "].columns must be a non-empty array.");
            return;
        }

        for (int c = 0; c < columns.size(); c++) {
            JsonNode column = columns.get(c);
            String path = "input_schema.lines[" + lineIndex + "].columns[" + c + "]";
            JsonNode max = column.path("max");
            if (max.isTextual()) {
                validateLengthReference(errors, max, definedScalars, path + ".max");
            }
            validateNumericBounds(errors, column, path);
        }
    }

    private void validateNumericBounds(List<String> errors, JsonNode node, String path) {
        JsonNode min = node.path("min");
        JsonNode max = node.path("max");
        if (isUnknown(min)) errors.add(path + ".min is unknown.");
        if (isUnknown(max)) errors.add(path + ".max is unknown.");
    }

    private void validateLengthReference(List<String> errors, JsonNode node, Set<String> definedScalars, String path) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            errors.add(path + " is missing.");
            return;
        }
        if (node.isNumber()) return;

        String value = node.asText("").trim();
        if (value.matches("\\d+")) return;
        if (value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            if (!definedScalars.contains(value) && !definedScalars.contains(value.toLowerCase(Locale.ROOT))) {
                errors.add(path + " references undefined scalar '" + value + "'.");
            }
            return;
        }
        if (value.matches("[A-Za-z_][A-Za-z0-9_]*\\s*-\\s*\\d+")) {
            String scalar = value.split("-")[0].trim();
            if (!definedScalars.contains(scalar) && !definedScalars.contains(scalar.toLowerCase(Locale.ROOT))) {
                errors.add(path + " references undefined scalar '" + scalar + "'.");
            }
            return;
        }
        errors.add(path + " must be a number, an earlier scalar, or scalar-minus-constant.");
    }

    private JsonNode firstPresent(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode candidate = node.path(name);
            if (!candidate.isMissingNode() && !candidate.isNull()) return candidate;
        }
        return node.path("missing");
    }

    private void requireText(List<String> errors, String field, String value) {
        if (value == null || value.isBlank()) {
            errors.add(field + " is missing.");
        }
    }

    private void validateNoUnknown(List<String> errors, String field, String value) {
        if (value == null) return;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("unknown") || lower.contains("not specified") || lower.contains("unspecified")) {
            errors.add(field + " contains unknown/unspecified details.");
        }
    }

    private boolean isUnknown(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return false;
        if (!node.isTextual()) return false;
        String value = node.asText("").trim().toLowerCase(Locale.ROOT);
        return value.equals("unknown") || value.equals("unspecified") || value.equals("not specified");
    }

    private boolean isIdentifier(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private String normalizeForComparison(String input) {
        if (input == null) return "";
        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFD);
        return DIACRITICS.matcher(decomposed)
                .replaceAll("")
                .replace('Đ', 'D')
                .replace('đ', 'd')
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\[\\] ]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private Set<String> significantTokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return tokens;
        for (String token : text.split("\\s+")) {
            if (token.length() < 3) continue;
            if (token.matches("\\d+")) continue;
            if (STOP_WORDS.contains(token)) continue;
            tokens.add(token);
        }
        return tokens;
    }

    private boolean hasStableSchemaGrounding(String normalizedSource, JsonNode schema) {
        if (normalizedSource == null || normalizedSource.isBlank()
                || schema == null || schema.isMissingNode() || schema.isNull()) {
            return false;
        }

        Set<String> identifiers = schemaIdentifiers(schema);
        int matchedIdentifiers = 0;
        for (String identifier : identifiers) {
            if (containsToken(normalizedSource, identifier)) {
                matchedIdentifiers++;
            }
        }

        Set<String> numbers = schemaNumbers(schema);
        int matchedNumbers = 0;
        for (String number : numbers) {
            if (containsToken(normalizedSource, number)) {
                matchedNumbers++;
            }
        }

        return matchedIdentifiers >= 2 || (matchedIdentifiers >= 1 && matchedNumbers >= 1);
    }

    private Set<String> schemaIdentifiers(JsonNode schema) {
        Set<String> identifiers = new LinkedHashSet<>();
        JsonNode lines = schema.path("lines");
        if (!lines.isArray()) return identifiers;

        for (JsonNode line : lines) {
            String name = normalizeIdentifier(line.path("name").asText(""));
            if (!name.isBlank()) identifiers.add(name);

            JsonNode fields = line.path("fields");
            if (fields.isArray()) {
                for (JsonNode field : fields) {
                    String fieldName = normalizeIdentifier(field.path("name").asText(""));
                    if (!fieldName.isBlank()) identifiers.add(fieldName);
                }
            }

            JsonNode columns = line.path("columns");
            if (columns.isArray()) {
                for (JsonNode column : columns) {
                    String columnName = normalizeIdentifier(column.path("name").asText(""));
                    if (!columnName.isBlank()) identifiers.add(columnName);
                }
            }
        }
        return identifiers;
    }

    private Set<String> schemaNumbers(JsonNode schema) {
        Set<String> numbers = new LinkedHashSet<>();
        collectSchemaNumbers(schema, numbers);
        return numbers;
    }

    private void collectSchemaNumbers(JsonNode node, Set<String> numbers) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isNumber()) {
            numbers.add(node.asText());
            return;
        }
        if (node.isTextual()) {
            String text = node.asText("").trim().replace(",", "");
            if (text.matches("\\d+")) numbers.add(text);
            if (text.matches("10\\^\\d+")) numbers.add(text.replace("^", ""));
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectSchemaNumbers(child, numbers));
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectSchemaNumbers(entry.getValue(), numbers));
        }
    }

    private String normalizeIdentifier(String identifier) {
        if (identifier == null) return "";
        return normalizeForComparison(identifier).replaceAll("[^a-z0-9_]", "");
    }

    private boolean containsToken(String normalizedText, String token) {
        if (token == null || token.isBlank()) return false;
        String normalizedToken = normalizeIdentifier(token);
        if (normalizedToken.isBlank()) return false;
        return Pattern.compile("(^|\\s)" + Pattern.quote(normalizedToken) + "(\\s|$)")
                .matcher(normalizedText)
                .find();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
