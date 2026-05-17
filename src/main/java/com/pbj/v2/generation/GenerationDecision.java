package com.pbj.v2.generation;

import java.util.List;

public record GenerationDecision(
        ProblemFamily family,
        GenerationPattern pattern,
        List<String> evidence) {
}
