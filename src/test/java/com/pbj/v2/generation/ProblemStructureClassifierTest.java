package com.pbj.v2.generation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemStructureClassifierTest {
    private final ProblemStructureClassifier classifier = new ProblemStructureClassifier();

    @Test
    void detectsCommandGridByStructureNotTitle() {
        String source = """
                There are two types of operations.
                UPDATE x y z W sets a value at coordinates.
                QUERY x1 y1 z1 x2 y2 z2 asks for a cuboid sum in a 3D matrix.
                """;

        assertThat(classifier.classify("Any Name", source)).isEqualTo(ProblemFamily.GRID);
    }

    @Test
    void detectsDigitProductFactorizationBySemanticsNotTitle() {
        String source = """
                Given n and k, choose exactly k digits from 1 to 9 such that their product is n.
                Output the smallest numeric sequence, or -1 if impossible.
                """;

        assertThat(classifier.classify("Any Name", source)).isEqualTo(ProblemFamily.DIGIT_PRODUCT_FACTORIZATION);
    }

    @Test
    void detectsGameTheoryFamily() {
        assertThat(classifier.classify("Any Name", "Players take turns; the player who cannot move loses. Determine who wins with optimal play."))
                .isEqualTo(ProblemFamily.GAME_THEORY);
    }

    @Test
    void detectsCowGameAsGameTheoryBeforeArraySignals() {
        String source = """
                The input contains multiple test cases. Each test case has N and N distinct integers a_i.
                Players alternate turns. They move the rightmost cow to any empty stall on its left.
                Determine who wins if both play optimally.
                """;

        assertThat(classifier.classify("A Game with Cows", source)).isEqualTo(ProblemFamily.GAME_THEORY);
    }

    @Test
    void detectsStaticMatrixAsGridBeforeArraySignals() {
        String source = """
                A land is described by a grid of size n x n square units.
                The next n lines contain n numbers on row i of the grid a.
                A robot moves from cell (1, 1) to cell (n, n).
                """;

        assertThat(classifier.classify("Danger Detection", source)).isEqualTo(ProblemFamily.GRID);
    }

    @Test
    void detectsRangeQueriesBeforePlainArray() {
        assertThat(classifier.classify("Range Sum", "Answer q range sum queries l r on an array."))
                .isEqualTo(ProblemFamily.RANGE_QUERY_UPDATE);
    }

    @Test
    void detectsDynamicProgrammingSeed() {
        assertThat(classifier.classify("Coin Change", "Find the minimum number of coins needed to make target."))
                .isEqualTo(ProblemFamily.DYNAMIC_PROGRAMMING);
    }

    @Test
    void fibonacciSequenceTextDoesNotBecomeArray() {
        String source = """
                Fibonacci numbers are defined recursively.
                The input contains two integers n and k.
                Print the value modulo 998244353.
                """;

        assertThat(classifier.classify("Fibonacci Power", source)).isEqualTo(ProblemFamily.NUMBER_THEORY);
    }

    @Test
    void detectsDataStructureFamily() {
        assertThat(classifier.classify("Fenwick", "Use a Fenwick tree for point updates and range sum queries."))
                .isEqualTo(ProblemFamily.DATA_STRUCTURE);
    }

    @Test
    void detectsGeneralFamily() {
        assertThat(classifier.classify("Pairs", "Solve with two pointers."))
                .isEqualTo(ProblemFamily.GENERAL);
    }

    @Test
    void detectsMinimumFeasibleAsGeneral() {
        assertThat(classifier.classify(
                "Magic Number",
                "Output the smallest n possible such that A is a divisor of n and C is a multiple of n."))
                .isEqualTo(ProblemFamily.GENERAL);
    }

    @Test
    void detectsConstructiveFamily() {
        assertThat(classifier.classify("Permutation", "Construct a permutation with even numbers first."))
                .isEqualTo(ProblemFamily.CONSTRUCTIVE);
    }

    @Test
    void detectsPermutationMappingAsCombinatoricsFromRealisticStatement() {
        assertThat(classifier.classify(
                "Exact Permutation Mapping",
                "All valid permutations are sorted in lexicographic order and numbered by permutation index."))
                .isEqualTo(ProblemFamily.COMBINATORICS);
    }

    @Test
    void detectsPolygonSubsetCountingAsCombinatoricsFromRealisticStatement() {
        assertThat(classifier.classify(
                "Intricate Polygons",
                "Help calculate how many different ways to choose sticks from the bag to create different polygons."))
                .isEqualTo(ProblemFamily.COMBINATORICS);
    }

    @Test
    void detectsStringKmpCountCorrectlyWithPatternKeyword() {
        assertThat(classifier.classify(
                "KMP Pattern Occurrences",
                "Given a text and a pattern, count all occurrences of the pattern in the text using KMP."))
                .isEqualTo(ProblemFamily.STRING);
    }

    @Test
    void genericPatternKeywordInGameDoesNotHijackToStringFamily() {
        assertThat(classifier.classify(
                "Game Play",
                "Determine the optimal pattern of moves for the player to win this game."))
                .isEqualTo(ProblemFamily.GAME_THEORY);
    }
}
