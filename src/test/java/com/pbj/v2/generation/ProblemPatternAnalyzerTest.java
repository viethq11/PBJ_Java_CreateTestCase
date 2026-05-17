package com.pbj.v2.generation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemPatternAnalyzerTest {
    private final ProblemPatternAnalyzer analyzer = new ProblemPatternAnalyzer();

    @Test
    void infersCowGameFromRulesInsteadOfTitle() {
        GenerationDecision decision = analyzer.analyze(
                "Any Title",
                """
                Players alternate turns. The player must move the rightmost cow to any empty stall on its left.
                Output Hieu if the first player wins, otherwise RR.
                """,
                ProblemFamily.GAME_THEORY);

        assertThat(decision.pattern()).isEqualTo(GenerationPattern.MAX_WELTER_COW_GAME);
        assertThat(decision.evidence()).contains("rightmost-cow move rule");
    }

    @Test
    void infersSubtractionGameFromMoveSet() {
        GenerationDecision decision = analyzer.analyze(
                "Any Title",
                "Players remove 1, 2, or 3 stones from one pile. Determine the winner.",
                ProblemFamily.GAME_THEORY);

        assertThat(decision.pattern()).isEqualTo(GenerationPattern.SUBTRACTION_GAME);
    }

    @Test
    void leavesUnknownGameWithoutTrustedPattern() {
        GenerationDecision decision = analyzer.analyze(
                "Any Title",
                "Two players take turns on a graph and determine the winner.",
                ProblemFamily.GAME_THEORY);

        assertThat(decision.pattern()).isEqualTo(GenerationPattern.UNKNOWN);
    }

    @Test
    void infersDangerDetectionGridPattern() {
        GenerationDecision decision = analyzer.analyze(
                "Danger Detection",
                """
                A land is described by a grid. A robot moves from cell (1, 1) to cell (n, n).
                A cell with load-bearing capacity <= G is dangerous.
                Determine the number of dangerous cells or the maximum safe weight.
                """,
                ProblemFamily.GRID);

        assertThat(decision.pattern()).isEqualTo(GenerationPattern.GRID_DANGER_DETECTION);
    }

    @Test
    void infersRangeSumPattern() {
        assertThat(analyzer.analyze("Range Sum", "Answer range sum queries l r.", ProblemFamily.RANGE_QUERY_UPDATE).pattern())
                .isEqualTo(GenerationPattern.RANGE_SUM_QUERIES);
    }

    @Test
    void infersOverflowPattern() {
        assertThat(analyzer.analyze("Large Sum", "The sum can exceed 32 bit and requires 64 bit.", ProblemFamily.NUMERIC_OVERFLOW_STRESS).pattern())
                .isEqualTo(GenerationPattern.ARRAY_SUM_OVERFLOW);
    }

    @Test
    void infersGraphShortestPathPattern() {
        assertThat(analyzer.analyze("Shortest Path", "Given a graph with vertices and edges, find the shortest path using Dijkstra.", ProblemFamily.GRAPH_TREE).pattern())
                .isEqualTo(GenerationPattern.GRAPH_SHORTEST_PATH);
    }

    @Test
    void infersGraphReachabilityPattern() {
        assertThat(analyzer.analyze("Reachability", "Use BFS to count reachable vertices in a graph.", ProblemFamily.GRAPH_TREE).pattern())
                .isEqualTo(GenerationPattern.GRAPH_REACHABILITY);
    }

    @Test
    void infersGraphDsuPattern() {
        assertThat(analyzer.analyze("Components", "Use DSU to count connected components.", ProblemFamily.GRAPH_TREE).pattern())
                .isEqualTo(GenerationPattern.GRAPH_DSU_COMPONENTS);
    }

    @Test
    void infersGraphToposortPattern() {
        assertThat(analyzer.analyze("Schedule", "Find a topological order of a DAG.", ProblemFamily.GRAPH_TREE).pattern())
                .isEqualTo(GenerationPattern.GRAPH_TOPOLOGICAL_ORDER);
    }

    @Test
    void infersCoinChangeDpPattern() {
        assertThat(analyzer.analyze("Coin Change", "Find the minimum number of coins.", ProblemFamily.DYNAMIC_PROGRAMMING).pattern())
                .isEqualTo(GenerationPattern.COIN_CHANGE_MIN_COINS);
    }

    @Test
    void infersKnapsackPattern() {
        assertThat(analyzer.analyze("Knapsack", "Solve the 0/1 knapsack problem.", ProblemFamily.DYNAMIC_PROGRAMMING).pattern())
                .isEqualTo(GenerationPattern.KNAPSACK_01);
    }

    @Test
    void infersLisPattern() {
        assertThat(analyzer.analyze("LIS", "Find the longest increasing subsequence.", ProblemFamily.DYNAMIC_PROGRAMMING).pattern())
                .isEqualTo(GenerationPattern.LIS_LENGTH);
    }

    @Test
    void infersBitmaskPattern() {
        assertThat(analyzer.analyze("Assignment", "Use bitmask DP for assignment.", ProblemFamily.DYNAMIC_PROGRAMMING).pattern())
                .isEqualTo(GenerationPattern.BITMASK_ASSIGNMENT);
    }

    @Test
    void infersFenwickPattern() {
        assertThat(analyzer.analyze("Fenwick", "Use a Fenwick tree for point updates and range sum queries.", ProblemFamily.DATA_STRUCTURE).pattern())
                .isEqualTo(GenerationPattern.FENWICK_POINT_UPDATE_RANGE_SUM);
    }

    @Test
    void infersSegmentTreePattern() {
        assertThat(analyzer.analyze("Segment", "Use a segment tree for range updates and range sum queries.", ProblemFamily.DATA_STRUCTURE).pattern())
                .isEqualTo(GenerationPattern.SEGMENT_TREE_RANGE_SUM_UPDATE);
    }

    @Test
    void infersBinarySearchPattern() {
        assertThat(analyzer.analyze("Lower Bound", "Find lower bound with binary search.", ProblemFamily.GENERAL).pattern())
                .isEqualTo(GenerationPattern.BINARY_SEARCH_LOWER_BOUND);
    }

    @Test
    void infersMinimumFeasiblePattern() {
        assertThat(analyzer.analyze(
                "Magic Number",
                "Print the smallest n possible such that A is a divisor of n, B is not a divisor of n, C is a multiple of n, and D is not a multiple of n.",
                ProblemFamily.GENERAL).pattern())
                .isEqualTo(GenerationPattern.BINARY_SEARCH_MIN_FEASIBLE);
    }

    @Test
    void infersTwoPointerPattern() {
        assertThat(analyzer.analyze("Pairs", "Use two pointers to count valid pairs.", ProblemFamily.GENERAL).pattern())
                .isEqualTo(GenerationPattern.TWO_POINTER_PAIR_COUNT);
    }

    @Test
    void infersGreedyPattern() {
        assertThat(analyzer.analyze("Intervals", "Greedy interval scheduling.", ProblemFamily.GENERAL).pattern())
                .isEqualTo(GenerationPattern.GREEDY_INTERVAL_SCHEDULING);
    }

    @Test
    void infersTreeDpPattern() {
        assertThat(analyzer.analyze("Tree Sum", "Compute subtree sum on a tree using tree dp.", ProblemFamily.GRAPH_TREE).pattern())
                .isEqualTo(GenerationPattern.TREE_DP_SUBTREE_SUM);
    }

    @Test
    void infersConstructivePattern() {
        assertThat(analyzer.analyze("Permutation", "Build a permutation with even numbers first.", ProblemFamily.CONSTRUCTIVE).pattern())
                .isEqualTo(GenerationPattern.CONSTRUCTIVE_EVEN_ODD_PERMUTATION);
    }

    @Test
    void infersFibonacciPowerPattern() {
        assertThat(analyzer.analyze(
                "Fibonacci Power",
                "Compute the sum of Fibonacci numbers raised to power k modulo 998244353.",
                ProblemFamily.NUMBER_THEORY).pattern())
                .isEqualTo(GenerationPattern.FIBONACCI_POWER_SUM);
    }

    @Test
    void infersGoalsStyleExpectedValueGameFromRealisticStatement() {
        assertThat(analyzer.analyze(
                "Goals",
                "Trong chooses his shooting strategy to maximize the score he can earn. Quang chooses his playing strategy to maximize the expected value of his total score if both players make their choices optimally.",
                ProblemFamily.GAME_THEORY).pattern())
                .isEqualTo(GenerationPattern.EXPECTED_VALUE_OPTIMAL_PLAY);
    }

    @Test
    void infersPermutationRankUnrankFromRealisticStatement() {
        assertThat(analyzer.analyze(
                "Exact Permutation Mapping",
                "All permutations are sorted in lexicographic order and numbered by permutation index. Given an index k, find the permutation; given a permutation, find the index.",
                ProblemFamily.COMBINATORICS).pattern())
                .isEqualTo(GenerationPattern.PERMUTATION_RANK_UNRANK);
    }

    @Test
    void infersPolygonSubsetCountingFromRealisticStatement() {
        assertThat(analyzer.analyze(
                "Intricate Polygons",
                "Help calculate how many different ways to choose sticks from the bag to create different polygons.",
                ProblemFamily.COMBINATORICS).pattern())
                .isEqualTo(GenerationPattern.POLYGON_SUBSET_COUNT);
    }

    @Test
    void infersWeightedSequenceSumFromRealisticStatement() {
        assertThat(analyzer.analyze(
                "Journey to Sequence Sum",
                "Find the sum of the first n terms where A_i = i^K * R^i modulo 1000000007.",
                ProblemFamily.NUMBER_THEORY).pattern())
                .isEqualTo(GenerationPattern.WEIGHTED_SEQUENCE_SUM);
    }

    @Test
    void infersInsertionInversionMinimizationFromRealisticStatement() {
        assertThat(analyzer.analyze(
                "Mingle Lineup",
                "Students from group B can be inserted anywhere. What is the minimum number of mistakes after inserting them?",
                ProblemFamily.GENERAL).pattern())
                .isEqualTo(GenerationPattern.INSERTION_INVERSION_MINIMIZATION);
    }
}
