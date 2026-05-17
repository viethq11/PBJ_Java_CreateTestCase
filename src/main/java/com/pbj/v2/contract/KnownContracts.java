package com.pbj.v2.contract;

import java.util.List;

public final class KnownContracts {
    private KnownContracts() {
    }

    public static ProblemContract powerTower() {
        ScalarField n = new ScalarField("n", Bound.of(1), Bound.of(50));
        ScalarField m = new ScalarField("m", Bound.of(1), Bound.of(30));
        Bound one = Bound.of(1);
        Bound nRef = Bound.ref("n");
        CommandVariant update = new CommandVariant("UPDATE", List.of(
                new ScalarField("x", one, nRef),
                new ScalarField("y", one, nRef),
                new ScalarField("z", one, nRef),
                new ScalarField("w", Bound.of(-1_000_000_000L), Bound.of(1_000_000_000L))
        ));
        CommandVariant query = new CommandVariant("QUERY", List.of(
                new ScalarField("x1", one, nRef),
                new ScalarField("y1", one, nRef),
                new ScalarField("z1", one, nRef),
                new ScalarField("x2", one, nRef),
                new ScalarField("y2", one, nRef),
                new ScalarField("z2", one, nRef)
        ));
        return new ProblemContract(
                "Power Tower",
                true,
                Bound.of(1),
                Bound.of(5),
                List.of(
                        InputSection.scalars(List.of(n, m)),
                        InputSection.commands("m", List.of(update, query))
                ),
                "Print one integer sum for each QUERY operation.");
    }

    public static ProblemContract gcdPair() {
        return new ProblemContract(
                "Greatest Common Divisor",
                false,
                null,
                null,
                List.of(InputSection.scalars(List.of(
                        new ScalarField("a", Bound.of(1), Bound.of(1_000_000_000L)),
                        new ScalarField("b", Bound.of(1), Bound.of(1_000_000_000L))
                ))),
                "Print gcd(a, b)."
        );
    }

    public static ProblemContract maximumSubarray() {
        return new ProblemContract(
                "Maximum Subarray Sum",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(new ScalarField("n", Bound.of(1), Bound.of(200)))),
                        InputSection.array("n", new ScalarField("a", Bound.of(-1_000_000_000L), Bound.of(1_000_000_000L)))
                ),
                "Print the maximum subarray sum."
        );
    }

    public static ProblemContract arraySumOverflow() {
        return new ProblemContract(
                "Array Sum 64-bit",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(new ScalarField("n", Bound.of(1), Bound.of(200)))),
                        InputSection.array("n", new ScalarField("a", Bound.of(-1_000_000_000L), Bound.of(1_000_000_000L)))
                ),
                "Print the sum of all values using 64-bit arithmetic."
        );
    }

    public static ProblemContract treeDistanceQueries() {
        return new ProblemContract(
                "Tree Distance Queries",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(
                                new ScalarField("n", Bound.of(2), Bound.of(60)),
                                new ScalarField("q", Bound.of(1), Bound.of(80)),
                                new ScalarField("e", Bound.of(1), Bound.ref("n"))
                        )),
                        InputSection.rows("e", List.of(
                                new ScalarField("u", Bound.of(1), Bound.ref("n")),
                                new ScalarField("v", Bound.of(1), Bound.ref("n"))
                        )),
                        InputSection.rows("q", List.of(
                                new ScalarField("u", Bound.of(1), Bound.ref("n")),
                                new ScalarField("v", Bound.of(1), Bound.ref("n"))
                        ))
                ),
                "Print the number of edges on each queried tree path."
        );
    }

    public static ProblemContract graphShortestPath() {
        return new ProblemContract(
                "Graph Shortest Path",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(
                                new ScalarField("n", Bound.of(2), Bound.of(30)),
                                new ScalarField("m", Bound.of(1), Bound.of(80)),
                                new ScalarField("s", Bound.of(1), Bound.ref("n")),
                                new ScalarField("t", Bound.of(1), Bound.ref("n"))
                        )),
                        InputSection.rows("m", List.of(
                                new ScalarField("u", Bound.of(1), Bound.ref("n")),
                                new ScalarField("v", Bound.of(1), Bound.ref("n")),
                                new ScalarField("w", Bound.of(1), Bound.of(1000))
                        ))
                ),
                "Print the shortest-path distance from s to t."
        );
    }

    public static ProblemContract graphReachability() {
        return new ProblemContract(
                "Graph Reachability",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(
                                new ScalarField("n", Bound.of(2), Bound.of(30)),
                                new ScalarField("m", Bound.of(1), Bound.of(80)),
                                new ScalarField("s", Bound.of(1), Bound.ref("n"))
                        )),
                        InputSection.rows("m", List.of(
                                new ScalarField("u", Bound.of(1), Bound.ref("n")),
                                new ScalarField("v", Bound.of(1), Bound.ref("n"))
                        ))
                ),
                "Print the number of vertices reachable from s."
        );
    }

    public static ProblemContract graphDsuComponents() {
        return new ProblemContract(
                "Connected Components",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(
                                new ScalarField("n", Bound.of(2), Bound.of(30)),
                                new ScalarField("m", Bound.of(0), Bound.of(80))
                        )),
                        InputSection.rows("m", List.of(
                                new ScalarField("u", Bound.of(1), Bound.ref("n")),
                                new ScalarField("v", Bound.of(1), Bound.ref("n"))
                        ))
                ),
                "Print the number of connected components."
        );
    }

    public static ProblemContract graphTopologicalOrder() {
        return new ProblemContract(
                "Topological Order",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(
                                new ScalarField("n", Bound.of(2), Bound.of(30)),
                                new ScalarField("m", Bound.of(1), Bound.of(80))
                        )),
                        InputSection.rows("m", List.of(
                                new ScalarField("u", Bound.of(1), Bound.ref("n")),
                                new ScalarField("v", Bound.of(1), Bound.ref("n"))
                        ))
                ),
                "Print one valid lexicographically smallest topological order."
        );
    }

    public static ProblemContract treeDpSubtreeSum() {
        return new ProblemContract(
                "Tree Subtree Sum",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(
                                new ScalarField("n", Bound.of(2), Bound.of(30)),
                                new ScalarField("e", Bound.of(1), Bound.ref("n"))
                        )),
                        InputSection.array("n", new ScalarField("value", Bound.of(-1000), Bound.of(1000))),
                        InputSection.rows("e", List.of(
                                new ScalarField("u", Bound.of(1), Bound.ref("n")),
                                new ScalarField("v", Bound.of(1), Bound.ref("n"))
                        ))
                ),
                "Print subtree sums for all vertices rooted at 1."
        );
    }

    public static ProblemContract stringHashSubstringEquality() {
        return new ProblemContract(
                "Substring Equality",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(
                                new ScalarField("n", Bound.of(1), Bound.of(20)),
                                new ScalarField("q", Bound.of(1), Bound.of(30))
                        )),
                        InputSection.array("n", new ScalarField("ch", Bound.of(1), Bound.of(26))),
                        InputSection.rows("q", List.of(
                                new ScalarField("l1", Bound.of(1), Bound.ref("n")),
                                new ScalarField("r1", Bound.of(1), Bound.ref("n")),
                                new ScalarField("l2", Bound.of(1), Bound.ref("n")),
                                new ScalarField("r2", Bound.of(1), Bound.ref("n"))
                        ))
                ),
                "Print YES when queried substrings are equal, otherwise NO."
        );
    }

    public static ProblemContract constructiveEvenOddPermutation() {
        return new ProblemContract(
                "Even Odd Permutation",
                false,
                null,
                null,
                List.of(InputSection.scalars(List.of(new ScalarField("n", Bound.of(1), Bound.of(30))))),
                "Print evens ascending followed by odds ascending."
        );
    }

    public static ProblemContract rangeSumQueries() {
        return new ProblemContract(
                "Range Sum Queries",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(
                                new ScalarField("n", Bound.of(1), Bound.of(100)),
                                new ScalarField("q", Bound.of(1), Bound.of(100))
                        )),
                        InputSection.array("n", new ScalarField("a", Bound.of(-1_000_000_000L), Bound.of(1_000_000_000L))),
                        InputSection.rows("q", List.of(
                                new ScalarField("l", Bound.of(1), Bound.ref("n")),
                                new ScalarField("r", Bound.of(1), Bound.ref("n"))
                        ))
                ),
                "Print one range sum for each query."
        );
    }

    public static ProblemContract fenwickPointUpdateRangeSum() {
        ScalarField n = new ScalarField("n", Bound.of(1), Bound.of(50));
        ScalarField q = new ScalarField("q", Bound.of(1), Bound.of(80));
        return new ProblemContract(
                "Fenwick Range Sum",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(n, q)),
                        InputSection.array("n", new ScalarField("a", Bound.of(-1000), Bound.of(1000))),
                        InputSection.commands("q", List.of(
                                new CommandVariant("ADD", List.of(
                                        new ScalarField("i", Bound.of(1), Bound.ref("n")),
                                        new ScalarField("delta", Bound.of(-1000), Bound.of(1000))
                                )),
                                new CommandVariant("SUM", List.of(
                                        new ScalarField("l", Bound.of(1), Bound.ref("n")),
                                        new ScalarField("r", Bound.of(1), Bound.ref("n"))
                                ))
                        ))
                ),
                "Process point updates and range-sum queries."
        );
    }

    public static ProblemContract segmentTreeRangeSumUpdate() {
        ScalarField n = new ScalarField("n", Bound.of(1), Bound.of(50));
        ScalarField q = new ScalarField("q", Bound.of(1), Bound.of(80));
        return new ProblemContract(
                "Segment Tree Range Sum",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(n, q)),
                        InputSection.array("n", new ScalarField("a", Bound.of(-1000), Bound.of(1000))),
                        InputSection.commands("q", List.of(
                                new CommandVariant("ADD", List.of(
                                        new ScalarField("l", Bound.of(1), Bound.ref("n")),
                                        new ScalarField("r", Bound.of(1), Bound.ref("n")),
                                        new ScalarField("delta", Bound.of(-1000), Bound.of(1000))
                                )),
                                new CommandVariant("SUM", List.of(
                                        new ScalarField("l", Bound.of(1), Bound.ref("n")),
                                        new ScalarField("r", Bound.of(1), Bound.ref("n"))
                                ))
                        ))
                ),
                "Process range-add updates and range-sum queries."
        );
    }

    public static ProblemContract binarySearchLowerBound() {
        return new ProblemContract(
                "Lower Bound",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(
                                new ScalarField("n", Bound.of(1), Bound.of(100)),
                                new ScalarField("x", Bound.of(-1_000_000), Bound.of(1_000_000))
                        )),
                        InputSection.array("n", new ScalarField("a", Bound.of(-1_000_000), Bound.of(1_000_000)))
                ),
                "Print the first 1-based position with a[i] >= x, or n+1 if absent."
        );
    }

    public static ProblemContract binarySearchMinFeasible() {
        return new ProblemContract(
                "Minimum Feasible Integer",
                false,
                null,
                null,
                List.of(InputSection.scalars(List.of(
                        new ScalarField("a", Bound.of(1), Bound.of(100)),
                        new ScalarField("b", Bound.of(1), Bound.of(100)),
                        new ScalarField("c", Bound.of(1), Bound.of(100)),
                        new ScalarField("d", Bound.of(1), Bound.of(100))
                ))),
                "Print the smallest n satisfying divisor/multiple constraints, or -1."
        );
    }

    public static ProblemContract twoPointerPairCount() {
        return new ProblemContract(
                "Pair Count",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(
                                new ScalarField("n", Bound.of(1), Bound.of(100)),
                                new ScalarField("target", Bound.of(-2_000_000), Bound.of(2_000_000))
                        )),
                        InputSection.array("n", new ScalarField("a", Bound.of(-1_000_000), Bound.of(1_000_000)))
                ),
                "Print the number of pairs i<j with a[i]+a[j] <= target."
        );
    }

    public static ProblemContract greedyIntervalScheduling() {
        return new ProblemContract(
                "Interval Scheduling",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(new ScalarField("n", Bound.of(1), Bound.of(100)))),
                        InputSection.rows("n", List.of(
                                new ScalarField("l", Bound.of(0), Bound.of(1000)),
                                new ScalarField("r", Bound.of(0), Bound.of(1000))
                        ))
                ),
                "Print the maximum number of pairwise non-overlapping intervals."
        );
    }

    public static ProblemContract coinChangeMinCoins() {
        return new ProblemContract(
                "Coin Change",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(
                                new ScalarField("n", Bound.of(1), Bound.of(20)),
                                new ScalarField("target", Bound.of(0), Bound.of(200))
                        )),
                        InputSection.array("n", new ScalarField("coin", Bound.of(1), Bound.of(100)))
                ),
                "Print the minimum number of coins, or -1 if impossible."
        );
    }

    public static ProblemContract knapsack01() {
        return new ProblemContract(
                "0/1 Knapsack",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(
                                new ScalarField("n", Bound.of(1), Bound.of(20)),
                                new ScalarField("capacity", Bound.of(1), Bound.of(100))
                        )),
                        InputSection.rows("n", List.of(
                                new ScalarField("weight", Bound.of(1), Bound.of(50)),
                                new ScalarField("value", Bound.of(1), Bound.of(100))
                        ))
                ),
                "Print the maximum achievable value."
        );
    }

    public static ProblemContract lisLength() {
        return new ProblemContract(
                "LIS Length",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(new ScalarField("n", Bound.of(1), Bound.of(100)))),
                        InputSection.array("n", new ScalarField("a", Bound.of(-1_000_000L), Bound.of(1_000_000L)))
                ),
                "Print the length of the longest strictly increasing subsequence."
        );
    }

    public static ProblemContract bitmaskAssignment() {
        return new ProblemContract(
                "Assignment DP",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(new ScalarField("n", Bound.of(1), Bound.of(8)))),
                        InputSection.rows("n", List.of(
                                new ScalarField("c1", Bound.of(0), Bound.of(100)),
                                new ScalarField("c2", Bound.of(0), Bound.of(100)),
                                new ScalarField("c3", Bound.of(0), Bound.of(100)),
                                new ScalarField("c4", Bound.of(0), Bound.of(100)),
                                new ScalarField("c5", Bound.of(0), Bound.of(100)),
                                new ScalarField("c6", Bound.of(0), Bound.of(100)),
                                new ScalarField("c7", Bound.of(0), Bound.of(100)),
                                new ScalarField("c8", Bound.of(0), Bound.of(100))
                        ))
                ),
                "Print the minimum assignment cost using one distinct column per row."
        );
    }

    public static ProblemContract fibonacciPowerSum() {
        return new ProblemContract(
                "Fibonacci Power",
                false,
                null,
                null,
                List.of(InputSection.scalars(List.of(
                        new ScalarField("n", Bound.of(1), Bound.of(20)),
                        new ScalarField("k", Bound.of(1), Bound.of(8))
                ))),
                "Print sum_{i=1..n} F(i)^k modulo MOD."
        );
    }

    public static ProblemContract polygonSubsetCount() {
        return new ProblemContract(
                "Polygon Subset Count",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(new ScalarField("n", Bound.of(1), Bound.of(14)))),
                        InputSection.array("n", new ScalarField("a", Bound.of(1), Bound.of(30)))
                ),
                "Print the number of subsets that can form a polygon."
        );
    }

    public static ProblemContract weightedSequenceSum() {
        return new ProblemContract(
                "Weighted Sequence Sum",
                true,
                Bound.of(1),
                Bound.of(4),
                List.of(InputSection.scalars(List.of(
                        new ScalarField("k", Bound.of(1), Bound.of(8)),
                        new ScalarField("n", Bound.of(1), Bound.of(20)),
                        new ScalarField("r", Bound.of(2), Bound.of(20))
                ))),
                "Print one modular sequence sum per test case."
        );
    }

    public static ProblemContract insertionInversionMinimization() {
        return new ProblemContract(
                "Insertion Inversion Minimization",
                true,
                Bound.of(1),
                Bound.of(4),
                List.of(
                        InputSection.scalars(List.of(
                                new ScalarField("n", Bound.of(1), Bound.of(12)),
                                new ScalarField("m", Bound.of(1), Bound.of(12))
                        )),
                        InputSection.array("n", new ScalarField("a", Bound.of(1), Bound.of(100))),
                        InputSection.array("m", new ScalarField("b", Bound.of(1), Bound.of(100)))
                ),
                "Print the minimum inversion count after inserting B into A."
        );
    }

    public static ProblemContract maxWelterGame() {
        return new ProblemContract(
                "A Game with Cows",
                true,
                Bound.of(1),
                Bound.of(6),
                List.of(
                        InputSection.scalars(List.of(new ScalarField("n", Bound.of(1), Bound.of(20)))),
                        InputSection.array("n", new ScalarField("a", Bound.of(1), Bound.of(1_000_000_000L)))
                ),
                "Print Hieu if the first player wins, otherwise RR."
        );
    }

    public static ProblemContract subtractionGame() {
        return new ProblemContract(
                "Subtraction Game",
                true,
                Bound.of(1),
                Bound.of(6),
                List.of(InputSection.scalars(List.of(new ScalarField("n", Bound.of(1), Bound.of(1_000_000_000L))))),
                "Print First if the starting player wins, otherwise Second."
        );
    }

    public static ProblemContract permutationRankUnrank() {
        Bound one = Bound.of(1);
        Bound eight = Bound.of(8);
        CommandVariant rank = new CommandVariant("RANK", List.of(
                new ScalarField("n", one, eight),
                new ScalarField("p1", one, eight),
                new ScalarField("p2", one, eight),
                new ScalarField("p3", one, eight),
                new ScalarField("p4", one, eight),
                new ScalarField("p5", one, eight),
                new ScalarField("p6", one, eight),
                new ScalarField("p7", one, eight),
                new ScalarField("p8", one, eight)
        ));
        CommandVariant unrank = new CommandVariant("UNRANK", List.of(
                new ScalarField("n", one, eight),
                new ScalarField("k", one, Bound.of(40320L))
        ));
        return new ProblemContract(
                "Permutation Mapping",
                true,
                Bound.of(1),
                Bound.of(5),
                List.of(
                        InputSection.scalars(List.of(new ScalarField("q", Bound.of(1), Bound.of(5)))),
                        InputSection.commands("q", List.of(rank, unrank))
                ),
                "Print rank or space-separated permutation."
        );
    }

    public static ProblemContract stringKmpCount() {
        return new ProblemContract(
                "KMP Pattern Occurrences",
                false,
                null,
                null,
                List.of(
                        InputSection.scalars(List.of(
                                new ScalarField("n", Bound.of(1), Bound.of(50)),
                                new ScalarField("m", Bound.of(1), Bound.of(20))
                        )),
                        InputSection.array("n", new ScalarField("s", Bound.of(1), Bound.of(26))),
                        InputSection.array("m", new ScalarField("p", Bound.of(1), Bound.of(26)))
                ),
                "Print the count of pattern occurrences."
        );
    }
}
