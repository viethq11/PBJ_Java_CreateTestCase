package com.pbj.v2.contract;

import java.util.List;

public record ProblemContract(
        String title,
        boolean multipleTestCases,
        Bound testCaseMin,
        Bound testCaseMax,
        List<InputSection> sections,
        String outputSemantics) {

    public ProblemContract {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Contract title is required.");
        }
        if (multipleTestCases && (testCaseMin == null || testCaseMax == null)) {
            throw new IllegalArgumentException("Multi-test contracts require T bounds.");
        }
        sections = sections == null ? List.of() : List.copyOf(sections);
    }
}
