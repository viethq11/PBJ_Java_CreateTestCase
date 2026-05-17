package com.pbj.v2.generation;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class ProblemStructureClassifier {

    public ProblemFamily classify(String title, String source) {
        String text = ((title == null ? "" : title) + "\n" + (source == null ? "" : source))
                .toLowerCase(Locale.ROOT);

        if (isCommandBased(text)) {
            return isGridLike(text) ? ProblemFamily.GRID : ProblemFamily.COMMAND_BASED;
        }
        if (isGridLike(text)) {
            return ProblemFamily.GRID;
        }
        if (isDigitProductFactorization(text)) {
            return ProblemFamily.DIGIT_PRODUCT_FACTORIZATION;
        }
        if (isDataStructure(text)) {
            return ProblemFamily.DATA_STRUCTURE;
        }
        if (isRangeQueryUpdate(text)) {
            return ProblemFamily.RANGE_QUERY_UPDATE;
        }
        if (isDynamicProgramming(text)) {
            return ProblemFamily.DYNAMIC_PROGRAMMING;
        }
        if (isNumberTheory(text)) {
            return ProblemFamily.NUMBER_THEORY;
        }
        if (isString(text)) {
            return ProblemFamily.STRING;
        }
        if (isCombinatorics(text)) {
            return ProblemFamily.COMBINATORICS;
        }
        if (isConstructive(text)) {
            return ProblemFamily.CONSTRUCTIVE;
        }
        if (isNumericOverflowStress(text)) {
            return ProblemFamily.NUMERIC_OVERFLOW_STRESS;
        }
        if (isGraphTree(text)) {
            return ProblemFamily.GRAPH_TREE;
        }
        if (isGameTheory(text)) {
            return ProblemFamily.GAME_THEORY;
        }
        if (isArray(text)) {
            return ProblemFamily.ARRAY;
        }
        if (isGeneral(text)) {
            return ProblemFamily.GENERAL;
        }
        if (isScalarOnly(text)) {
            return ProblemFamily.SCALAR_ONLY;
        }
        return ProblemFamily.UNKNOWN;
    }

    private boolean isCommandBased(String text) {
        return containsAll(text, "update", "query")
                || text.contains("two types of operations")
                || text.contains("operations in one of the")
                || text.contains("m lines contain operations")
                || text.contains("instruction query")
                || text.contains("command");
    }

    private boolean isGridLike(String text) {
        if (text.contains("gcd") || text.contains("greatest common divisor")) {
            return false;
        }
        return text.contains("grid")
                || text.contains("matrix")
                || text.contains("square units")
                || containsAll(text, "rows", "columns", "cell")
                || text.contains("cube")
                || text.contains("3d")
                || containsAll(text, "x", "y", "z", "x1", "y1", "z1")
                || text.contains("coordinates");
    }

    private boolean isDigitProductFactorization(String text) {
        return containsAll(text, "product", "digit")
                || containsAll(text, "digits", "1..9")
                || containsAll(text, "digits", "1 to 9")
                || containsAll(text, "factor", "smallest")
                || containsAll(text, "choose exactly", "digits");
    }

    private boolean isRangeQueryUpdate(String text) {
        return containsAll(text, "range", "query")
                || containsAll(text, "sum", "queries")
                || containsAll(text, "l", "r", "query");
    }

    private boolean isDynamicProgramming(String text) {
        return text.contains("dynamic programming")
                || text.contains("minimum number of coins")
                || text.contains("coin change")
                || text.contains("ways to make")
                || text.contains("knapsack")
                || text.contains("longest increasing subsequence")
                || text.contains("bitmask");
    }

    private boolean isCombinatorics(String text) {
        return containsAll(text, "permutation", "lexicographic", "index")
                || containsAll(text, "how many different ways", "polygon")
                || containsAll(text, "different ways", "choose", "polygon")
                || text.contains("polygon")
                || text.contains("rank")
                || text.contains("unrank");
    }

    private boolean isNumberTheory(String text) {
        return text.contains("fibonacci")
                || text.contains("modulo")
                || text.contains("mod ")
                || text.contains("modular")
                || text.contains("prime");
    }

    private boolean isDataStructure(String text) {
        return text.contains("segment tree")
                || text.contains("fenwick")
                || text.contains("binary indexed tree");
    }

    private boolean isString(String text) {
        return text.contains("string")
                || text.contains("substring")
                || (text.contains("pattern") && (text.contains("match") || text.contains("character") || text.contains("alphabet") || text.contains("text")));
    }

    private boolean isNumericOverflowStress(String text) {
        return text.contains("64-bit")
                || text.contains("long long")
                || containsAll(text, "sum", "can exceed", "32-bit")
                || containsAll(text, "sum", "exceed", "32-bit");
    }

    private boolean isGraphTree(String text) {
        return (text.contains("tree") && !text.contains("street"))
                || text.contains("graph")
                || text.contains("vertices")
                || text.contains("nodes")
                || text.contains("edges")
                || text.contains("n-1 edges")
                || text.contains("path between")
                || text.contains("dag")
                || text.contains("topological");
    }

    private boolean isGameTheory(String text) {
        if (text.contains("game") && (text.contains("stones") || text.contains("pile") || text.contains("cow") || text.contains("turn") || text.contains("play") || text.contains("player"))) {
            return true;
        }
        return (text.contains("game") || text.contains("player") || text.contains("turns") || text.contains("cow") || text.contains("pile") || text.contains("stones") || text.contains("expected value") || text.contains("expected score"))
                && (text.contains("winner") || text.contains("wins") || text.contains("loses") || text.contains("optimal") || text.contains("play") || text.contains("choices"));
    }

    private boolean isArray(String text) {
        return text.contains("array")
                || text.contains("permutation")
                || text.contains("a_i")
                || text.contains("n integers")
                || containsAll(text, "second line", "n integers")
                || text.contains("subarray")
                || text.contains("kadane");
    }

    private boolean isGeneral(String text) {
        return text.contains("binary search")
                || containsAll(text, "smallest", "possible")
                || text.contains("two pointers")
                || text.contains("two pointer")
                || text.contains("greedy")
                || text.contains("interval scheduling")
                || containsAll(text, "insert", "minimum number of mistakes");
    }

    private boolean isConstructive(String text) {
        return text.contains("construct")
                || text.contains("output any")
                || text.contains("build a permutation");
    }

    private boolean isScalarOnly(String text) {
        return (text.contains("input contains")
                || text.contains("gcd")
                || text.contains("greatest common divisor")
                || text.contains("ucln"))
                && !isArray(text)
                && !isCommandBased(text)
                && !isGraphTree(text);
    }

    private boolean containsAll(String text, String... needles) {
        for (String needle : needles) {
            if (!text.contains(needle)) return false;
        }
        return true;
    }
}
