package com.pbj.v2.contract;

import java.util.List;

public record InputSection(Kind kind, List<ScalarField> fields, String repeatField, List<CommandVariant> variants) {
    public enum Kind {
        SCALARS,
        ARRAY,
        ROWS,
        COMMANDS
    }

    public static InputSection scalars(List<ScalarField> fields) {
        return new InputSection(Kind.SCALARS, fields, null, List.of());
    }

    public static InputSection commands(String repeatField, List<CommandVariant> variants) {
        return new InputSection(Kind.COMMANDS, List.of(), repeatField, variants);
    }

    public static InputSection array(String repeatField, ScalarField field) {
        return new InputSection(Kind.ARRAY, List.of(field), repeatField, List.of());
    }

    public static InputSection rows(String repeatField, List<ScalarField> fields) {
        return new InputSection(Kind.ROWS, fields, repeatField, List.of());
    }

    public InputSection {
        if (kind == null) {
            throw new IllegalArgumentException("Section kind is required.");
        }
        fields = fields == null ? List.of() : List.copyOf(fields);
        variants = variants == null ? List.of() : List.copyOf(variants);
    }
}
