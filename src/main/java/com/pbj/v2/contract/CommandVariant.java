package com.pbj.v2.contract;

import java.util.List;

public record CommandVariant(String keyword, List<ScalarField> args) {
    public CommandVariant {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("Command keyword is required.");
        }
        args = args == null ? List.of() : List.copyOf(args);
    }
}
