package com.pbj.v2.generation;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class ProblemPatternAnalyzer {
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}");

    public GenerationDecision analyze(String title, String source, ProblemFamily family) {
        String text = normalize((title == null ? "" : title) + "\n" + (source == null ? "" : source));
        List<String> evidence = new ArrayList<>();
        GenerationPattern pattern = switch (family) {
            case SCALAR_ONLY -> scalarPattern(text, evidence);
            case ARRAY -> arrayPattern(text, evidence);
            case GRAPH_TREE -> graphTreePattern(text, evidence);
            case GRID -> gridPattern(text, evidence);
            case COMMAND_BASED -> commandPattern(text, evidence);
            case RANGE_QUERY_UPDATE -> rangePattern(text, evidence);
            case NUMERIC_OVERFLOW_STRESS -> overflowPattern(text, evidence);
            case DYNAMIC_PROGRAMMING -> dpPattern(text, evidence);
            case NUMBER_THEORY -> numberTheoryPattern(text, evidence);
            case DATA_STRUCTURE -> dataStructurePattern(text, evidence);
            case STRING -> stringPattern(text, evidence);
            case GENERAL -> generalPattern(text, evidence);
            case COMBINATORICS -> combinatoricsPattern(text, evidence);
            case CONSTRUCTIVE -> constructivePattern(text, evidence);
            case DIGIT_PRODUCT_FACTORIZATION -> digitProductPattern(text, evidence);
            case GAME_THEORY -> gamePattern(text, evidence);
            default -> GenerationPattern.UNKNOWN;
        };
        return new GenerationDecision(family, pattern, List.copyOf(evidence));
    }

    private GenerationPattern scalarPattern(String text, List<String> evidence) {
        if (containsAny(text, "greatest common divisor", "gcd", "ucln")) {
            evidence.add("scalar gcd semantics");
            return GenerationPattern.GCD_PAIR;
        }
        return GenerationPattern.UNKNOWN;
    }

    private GenerationPattern arrayPattern(String text, List<String> evidence) {
        if (containsAll(text, "maximum", "subarray")
                || containsAll(text, "maximum", "contiguous", "sum")
                || containsAny(text, "kadane")) {
            evidence.add("array maximum-subarray semantics");
            return GenerationPattern.MAXIMUM_SUBARRAY;
        }
        return GenerationPattern.UNKNOWN;
    }

    private GenerationPattern overflowPattern(String text, List<String> evidence) {
        if (containsAny(text, "64 bit", "long long")
                || containsAll(text, "sum", "can exceed", "32 bit")
                || containsAll(text, "sum", "10 9")) {
            evidence.add("sum requires widened accumulator");
            return GenerationPattern.ARRAY_SUM_OVERFLOW;
        }
        return GenerationPattern.UNKNOWN;
    }

    private GenerationPattern graphTreePattern(String text, List<String> evidence) {
        if (text.contains("tree")
                && containsAny(text, "distance", "number of edges on the unique path", "path length")) {
            evidence.add("tree plus path-distance semantics");
            return GenerationPattern.TREE_DISTANCE_QUERIES;
        }
        if (text.contains("tree")
                && containsAny(text, "subtree sum", "sum of values in each subtree", "tree dp")) {
            evidence.add("tree dynamic-programming subtree aggregation");
            return GenerationPattern.TREE_DP_SUBTREE_SUM;
        }
        if (containsAny(text, "shortest path", "dijkstra")
                && containsAny(text, "graph", "vertices", "edges")) {
            evidence.add("weighted graph shortest-path semantics");
            return GenerationPattern.GRAPH_SHORTEST_PATH;
        }
        if (containsAny(text, "connected components", "same component", "disjoint set union", "dsu")) {
            evidence.add("graph connectivity/union-find semantics");
            return GenerationPattern.GRAPH_DSU_COMPONENTS;
        }
        if (containsAny(text, "topological order", "toposort", "dag")) {
            evidence.add("dag ordering semantics");
            return GenerationPattern.GRAPH_TOPOLOGICAL_ORDER;
        }
        if (containsAny(text, "reachable", "bfs", "dfs")) {
            evidence.add("graph reachability semantics");
            return GenerationPattern.GRAPH_REACHABILITY;
        }
        return GenerationPattern.UNKNOWN;
    }

    private GenerationPattern commandPattern(String text, List<String> evidence) {
        if (containsAll(text, "update", "query")
                && containsAny(text, "3d", "cube", "x y z", "x1 y1 z1")) {
            evidence.add("update/query stream over 3d coordinates");
            return GenerationPattern.POWER_TOWER_3D_COMMANDS;
        }
        return GenerationPattern.UNKNOWN;
    }

    private GenerationPattern gridPattern(String text, List<String> evidence) {
        if (containsAny(text, "dangerous cells", "dangerous cell")
                && containsAny(text, "load bearing capacity", "load-bearing capacity")
                && containsAll(text, "cell 1 1", "cell n n")) {
            evidence.add("grid path with dangerous-cell threshold semantics");
            return GenerationPattern.GRID_DANGER_DETECTION;
        }
        return commandPattern(text, evidence);
    }

    private GenerationPattern rangePattern(String text, List<String> evidence) {
        if (containsAny(text, "range sum query", "sum queries", "sum of a l to a r")
                || containsAll(text, "query", "l", "r", "sum")) {
            evidence.add("range-sum query semantics");
            return GenerationPattern.RANGE_SUM_QUERIES;
        }
        return GenerationPattern.UNKNOWN;
    }

    private GenerationPattern dpPattern(String text, List<String> evidence) {
        if (containsAny(text, "minimum number of coins", "coin change")) {
            evidence.add("coin-change minimization semantics");
            return GenerationPattern.COIN_CHANGE_MIN_COINS;
        }
        if (text.contains("knapsack")) {
            evidence.add("0/1 knapsack semantics");
            return GenerationPattern.KNAPSACK_01;
        }
        if (containsAny(text, "longest increasing subsequence", "lis")) {
            evidence.add("longest increasing subsequence semantics");
            return GenerationPattern.LIS_LENGTH;
        }
        if (text.contains("bitmask")) {
            evidence.add("bitmask assignment semantics");
            return GenerationPattern.BITMASK_ASSIGNMENT;
        }
        return GenerationPattern.UNKNOWN;
    }

    private GenerationPattern numberTheoryPattern(String text, List<String> evidence) {
        if (containsAll(text, "fibonacci", "power")
                && containsAny(text, "modulo", "mod")) {
            evidence.add("fibonacci power sum modulo semantics");
            return GenerationPattern.FIBONACCI_POWER_SUM;
        }
        if (containsAll(text, "sum", "first", "terms")
                && containsAny(text, "i k", "i^k", "r i", "r^i")
                && containsAny(text, "modulo", "mod")) {
            evidence.add("weighted sequence-sum modulo semantics");
            return GenerationPattern.WEIGHTED_SEQUENCE_SUM;
        }
        if (containsAny(text, "prime count", "number of primes", "count primes")) {
            evidence.add("prime counting semantics");
            return GenerationPattern.PRIME_COUNT;
        }
        if (containsAny(text, "ncr", "binomial coefficient", "combinations")) {
            evidence.add("basic combinatorics semantics");
            return GenerationPattern.NCR_MOD;
        }
        return GenerationPattern.UNKNOWN;
    }

    private GenerationPattern dataStructurePattern(String text, List<String> evidence) {
        if (text.contains("segment tree")) {
            evidence.add("segment-tree range update/query semantics");
            return GenerationPattern.SEGMENT_TREE_RANGE_SUM_UPDATE;
        }
        if (containsAny(text, "fenwick", "binary indexed tree")) {
            evidence.add("fenwick point-update/range-query semantics");
            return GenerationPattern.FENWICK_POINT_UPDATE_RANGE_SUM;
        }
        return GenerationPattern.UNKNOWN;
    }

    private GenerationPattern stringPattern(String text, List<String> evidence) {
        if (containsAny(text, "substring equality", "rolling hash", "hashing")) {
            evidence.add("substring equality semantics");
            return GenerationPattern.STRING_SUBSTRING_EQUALITY;
        }
        if (containsAny(text, "kmp", "pattern occurrences", "count occurrences")) {
            evidence.add("pattern matching semantics");
            return GenerationPattern.STRING_KMP_COUNT;
        }
        return GenerationPattern.UNKNOWN;
    }

    private GenerationPattern constructivePattern(String text, List<String> evidence) {
        if (containsAny(text, "even numbers first", "evens then odds", "build a permutation")) {
            evidence.add("deterministic constructive permutation semantics");
            return GenerationPattern.CONSTRUCTIVE_EVEN_ODD_PERMUTATION;
        }
        return GenerationPattern.UNKNOWN;
    }

    private GenerationPattern combinatoricsPattern(String text, List<String> evidence) {
        if (containsAll(text, "permutation", "lexicographic", "index")
                && containsAny(text, "find the permutation", "find the index")) {
            evidence.add("permutation ranking/unranking semantics");
            return GenerationPattern.PERMUTATION_RANK_UNRANK;
        }
        if (containsAny(text, "how many different ways", "number of different polygons")
                && containsAny(text, "sticks", "polygon")) {
            evidence.add("subset-count polygon combinatorics semantics");
            return GenerationPattern.POLYGON_SUBSET_COUNT;
        }
        return GenerationPattern.UNKNOWN;
    }

    private GenerationPattern generalPattern(String text, List<String> evidence) {
        if (containsAny(text, "lower bound", "binary search")) {
            evidence.add("binary-search lower-bound semantics");
            return GenerationPattern.BINARY_SEARCH_LOWER_BOUND;
        }
        if (containsAll(text, "smallest", "possible")
                && containsAny(text, "divisor", "multiple", "not a divisor", "not a multiple")) {
            evidence.add("minimum feasible integer under monotone divisibility constraints");
            return GenerationPattern.BINARY_SEARCH_MIN_FEASIBLE;
        }
        if (containsAny(text, "two pointers", "two pointer")) {
            evidence.add("two-pointer pair-count semantics");
            return GenerationPattern.TWO_POINTER_PAIR_COUNT;
        }
        if (containsAny(text, "interval scheduling", "maximum number of non-overlapping intervals", "greedy")) {
            evidence.add("greedy interval scheduling semantics");
            return GenerationPattern.GREEDY_INTERVAL_SCHEDULING;
        }
        if (containsAll(text, "insert", "minimum number of mistakes")
                || containsAll(text, "minimum", "inversions", "insert")) {
            evidence.add("insertion order minimizing inversions");
            return GenerationPattern.INSERTION_INVERSION_MINIMIZATION;
        }
        return GenerationPattern.UNKNOWN;
    }

    private GenerationPattern digitProductPattern(String text, List<String> evidence) {
        if (containsAll(text, "digit", "product")) {
            evidence.add("digit-product semantics");
            return GenerationPattern.DIGIT_PRODUCT_FACTORIZATION;
        }
        return GenerationPattern.UNKNOWN;
    }

    private GenerationPattern gamePattern(String text, List<String> evidence) {
        if (containsAll(text, "rightmost", "cow")
                && containsAll(text, "empty", "stall")
                && containsAny(text, "hieu", "rr")) {
            evidence.add("rightmost-cow move rule");
            evidence.add("winner labels Hieu/RR");
            return GenerationPattern.MAX_WELTER_COW_GAME;
        }
        if (containsAny(text, "remove 1, 2, or 3", "remove 1 2 or 3", "take 1 2 or 3")
                && containsAny(text, "stones", "pile")) {
            evidence.add("take-away game with moves {1,2,3}");
            return GenerationPattern.SUBTRACTION_GAME;
        }
        if (containsAny(text, "expected value", "expected score")
                && containsAny(text, "optimal strategy", "play optimally", "make their choices optimally")) {
            evidence.add("expected-value game under optimal play");
            return GenerationPattern.EXPECTED_VALUE_OPTIMAL_PLAY;
        }
        return GenerationPattern.UNKNOWN;
    }

    private boolean containsAll(String text, String... needles) {
        for (String needle : needles) {
            if (!text.contains(needle)) return false;
        }
        return true;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    private String normalize(String input) {
        String decomposed = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFD);
        return DIACRITICS.matcher(decomposed)
                .replaceAll("")
                .replace('Đ', 'D')
                .replace('đ', 'd')
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\- ]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
