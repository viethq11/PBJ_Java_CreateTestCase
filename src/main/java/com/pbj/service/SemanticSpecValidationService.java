package com.pbj.service;

import com.pbj.dto.SemanticSpecDTO;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SemanticSpecValidationService {
    private static final Pattern PATH_CONDITION = Pattern.compile("path\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*,\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)");

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

    private String pathKey(String from, String to) {
        return cleanVariable(from).toLowerCase(Locale.ROOT) + "->" + cleanVariable(to).toLowerCase(Locale.ROOT);
    }
}
