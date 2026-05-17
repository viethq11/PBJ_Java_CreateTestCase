package com.pbj.v2.contract;

public record ScalarField(String name, Bound min, Bound max) {
    public ScalarField {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Field name is required.");
        }
        if (min == null || max == null) {
            throw new IllegalArgumentException("Field bounds are required.");
        }
    }
}
