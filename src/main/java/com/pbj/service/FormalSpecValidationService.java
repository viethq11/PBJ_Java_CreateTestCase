package com.pbj.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.pbj.dto.AiResponseDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class FormalSpecValidationService {

    private static final Set<String> SUPPORTED_LINE_KINDS = Set.of(
            "scalars", "array", "matrix", "grid", "edges", "queries", "string", "raw_lines");

    public void validateForGeneration(AiResponseDTO dto) {
        List<String> errors = new ArrayList<>();

        if (dto == null) {
            throw new IllegalStateException("Formal spec validation failed: AI response is empty.");
        }

        requireText(errors, "input_format", dto.getInputFormat());
        requireText(errors, "output_format", dto.getOutputFormat());
        requireText(errors, "constraints", dto.getConstraints());
        requireText(errors, "validator_code", dto.getValidatorCode());
        requireText(errors, "golden_solution", dto.getGoldenSolution());

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
}
