package com.pbj.service;

import com.pbj.dto.AiProblemAnalysisDTO;
import com.pbj.dto.SemanticSpecDTO;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SemanticSpecValidationService {
    private static final Pattern PATH_CONDITION = Pattern.compile("path\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*,\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)");

    public SemanticSpecDTO normalizeAndValidate(SemanticSpecDTO spec, AiProblemAnalysisDTO analysis) {
        if (spec == null) {
            throw new IllegalStateException("Semantic spec is missing.");
        }

        SemanticSpecDTO normalized = new SemanticSpecDTO();
        normalized.setQueryVariables(uniqueClean(spec.getQueryVariables()));
        normalized.setIgnoredVariables(uniqueClean(spec.getIgnoredVariables()));
        normalized.setPaths(cleanPaths(spec.getPaths()));
        normalized.setConditions(cleanConditions(spec.getConditions()));
        normalized.setGraphType(cleanLower(firstNonBlank(spec.getGraphType(),
                analysis == null ? null : analysis.getInputPattern())));
        normalized.setValueDomain(spec.getValueDomain());
        normalized.setInputModel(firstJson(spec.getInputModel(), analysis == null ? null : analysis.getInputModel()));
        normalized.setConstraints(spec.getConstraints());
        normalized.setCountedObjects(uniqueClean(spec.getCountedObjects()));
        normalized.setOutputSemantics(cleanText(spec.getOutputSemantics()));
        validate(normalized);
        return normalized;
    }

    public void validate(SemanticSpecDTO spec) {
        if (spec == null) {
            throw new IllegalStateException("Semantic spec is missing.");
        }

        Set<String> queryVariables = requireUniqueVariables(spec.getQueryVariables(), "query_variables");
        Set<String> ignoredVariables = requireSubset(spec.getIgnoredVariables(), queryVariables, "ignored_variables");
        Set<String> pathPairs = new HashSet<>();

        List<List<String>> paths = spec.getPaths();
        if (paths != null) {
            for (List<String> path : paths) {
                if (path == null || path.size() != 2) {
                    throw new IllegalStateException("Every semantic path must contain exactly two endpoint variables.");
                }
                String from = cleanVariable(path.get(0));
                String to = cleanVariable(path.get(1));
                if (!queryVariables.isEmpty() && (!queryVariables.contains(from) || !queryVariables.contains(to))) {
                    throw new IllegalStateException("Semantic path uses variable not present in query_variables: " + from + "," + to);
                }
                if (ignoredVariables.contains(from) || ignoredVariables.contains(to)) {
                    throw new IllegalStateException("Ignored query variable is reused as a path endpoint: " + from + "," + to);
                }
                pathPairs.add(pathKey(from, to));
            }
        }

        List<String> conditions = spec.getConditions();
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalStateException("Semantic spec must freeze at least one condition.");
        }
        for (String condition : conditions) {
            if (condition == null || condition.isBlank()) {
                throw new IllegalStateException("Semantic condition must not be blank.");
            }
            Matcher matcher = PATH_CONDITION.matcher(condition);
            while (matcher.find()) {
                String from = matcher.group(1);
                String to = matcher.group(2);
                if (!pathPairs.isEmpty() && !pathPairs.contains(pathKey(from, to))) {
                    throw new IllegalStateException("Condition references a path not frozen in semantic paths: " + condition);
                }
            }
        }

        if (spec.getGraphType() == null || spec.getGraphType().isBlank()) {
            throw new IllegalStateException("Semantic graph_type is missing.");
        }
    }

    private Set<String> requireUniqueVariables(List<String> variables, String fieldName) {
        Set<String> seen = new HashSet<>();
        if (variables == null) return seen;
        for (String variable : variables) {
            String clean = cleanVariable(variable);
            if (clean.isBlank()) {
                throw new IllegalStateException(fieldName + " contains a blank variable.");
            }
            if (!seen.add(clean)) {
                throw new IllegalStateException(fieldName + " contains duplicate variable: " + clean);
            }
        }
        return seen;
    }

    private Set<String> requireSubset(List<String> variables, Set<String> allowed, String fieldName) {
        Set<String> seen = requireUniqueVariables(variables, fieldName);
        if (!allowed.isEmpty()) {
            for (String variable : seen) {
                if (!allowed.contains(variable)) {
                    throw new IllegalStateException(fieldName + " contains variable not present in query_variables: " + variable);
                }
            }
        }
        return seen;
    }

    private String cleanVariable(String variable) {
        return variable == null ? "" : variable.trim();
    }

    private List<String> uniqueClean(List<String> values) {
        if (values == null) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : values) {
            String clean = cleanText(value);
            if (!clean.isBlank()) out.add(clean);
        }
        return new ArrayList<>(out);
    }

    private List<List<String>> cleanPaths(List<List<String>> paths) {
        if (paths == null) return List.of();
        List<List<String>> out = new ArrayList<>();
        for (List<String> path : paths) {
            if (path == null) continue;
            List<String> clean = uniqueClean(path);
            if (!clean.isEmpty()) out.add(clean);
        }
        return out;
    }

    private List<String> cleanConditions(List<String> conditions) {
        if (conditions == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String condition : conditions) {
            String clean = cleanText(condition)
                    .replaceAll("\\s+", " ")
                    .replace("path(", "path(")
                    .trim();
            if (!clean.isBlank()) out.add(clean);
        }
        return out;
    }

    private JsonNode firstJson(JsonNode preferred, JsonNode fallback) {
        return preferred != null && !preferred.isMissingNode() && !preferred.isNull() ? preferred : fallback;
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private String cleanLower(String value) {
        String clean = cleanText(value);
        return clean.isBlank() ? clean : clean.toLowerCase(Locale.ROOT);
    }

    private String cleanText(String value) {
        return value == null ? "" : value.trim();
    }

    private String pathKey(String from, String to) {
        return cleanVariable(from).toLowerCase(Locale.ROOT) + "->" + cleanVariable(to).toLowerCase(Locale.ROOT);
    }
}
