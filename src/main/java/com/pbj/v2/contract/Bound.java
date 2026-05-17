package com.pbj.v2.contract;

import java.util.Map;

public record Bound(String expression) {
    public static Bound of(long value) {
        return new Bound(Long.toString(value));
    }

    public static Bound ref(String variableName) {
        return new Bound(variableName);
    }

    public long resolve(Map<String, Long> variables) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Missing bound expression.");
        }
        String trimmed = expression.trim();
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
            Long value = variables.get(trimmed);
            if (value == null) {
                throw new IllegalArgumentException("Unknown bound reference: " + trimmed);
            }
            return value;
        }
    }
}
