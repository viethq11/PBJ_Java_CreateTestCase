package com.pbj.v2.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class V2EvaluationCorpusRunnerTest {

    private final ProblemStructureClassifier classifier = new ProblemStructureClassifier();
    private final ProblemPatternAnalyzer analyzer = new ProblemPatternAnalyzer();
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    record CorpusItem(
            String title,
            String description,
            ProblemFamily expectedFamily,
            GenerationPattern expectedPattern
    ) {}

    @Test
    void runEvaluationSuite() throws IOException {
        List<CorpusItem> corpus = new ArrayList<>();

        // --- SCALAR_ONLY ---
        corpus.add(new CorpusItem(
                "GCD of Two Numbers",
                "Given two positive integers a and b, find their greatest common divisor.",
                ProblemFamily.SCALAR_ONLY,
                GenerationPattern.GCD_PAIR
        ));
        corpus.add(new CorpusItem(
                "Sweet Chocolate Sharing",
                "Quang wants to split a chocolate grid. To make the pieces even, he needs to compute the greatest common divisor gcd(a, b) of the two bar dimensions.",
                ProblemFamily.SCALAR_ONLY,
                GenerationPattern.GCD_PAIR
        ));

        // --- ARRAY ---
        corpus.add(new CorpusItem(
                "Maximum Subarray Sum",
                "Given an integer array, find the contiguous subarray with the largest sum.",
                ProblemFamily.ARRAY,
                GenerationPattern.MAXIMUM_SUBARRAY
        ));
        corpus.add(new CorpusItem(
                "Optimal Shop Selection",
                "A tourist walks along a street with shops. Each shop has a profit or loss. Find the contiguous sequence of shops that maximizes his total profit, matching the kadane contiguous maximum subarray sum.",
                ProblemFamily.ARRAY,
                GenerationPattern.MAXIMUM_SUBARRAY
        ));

        // --- NUMERIC_OVERFLOW_STRESS ---
        corpus.add(new CorpusItem(
                "Large Array Sum",
                "Sum n integers. The values are up to 10^9 and the total sum can exceed 32-bit signed integer range, requiring a 64-bit long long accumulator.",
                ProblemFamily.NUMERIC_OVERFLOW_STRESS,
                GenerationPattern.ARRAY_SUM_OVERFLOW
        ));

        // --- GRAPH_TREE ---
        corpus.add(new CorpusItem(
                "Tree Distance",
                "Given a tree with n vertices and n-1 edges, answer queries asking for the unique path distance between u and v.",
                ProblemFamily.GRAPH_TREE,
                GenerationPattern.TREE_DISTANCE_QUERIES
        ));
        corpus.add(new CorpusItem(
                "Tree Subtree Aggregation",
                "Compute the sum of values in each subtree of a tree rooted at 1 using tree dp.",
                ProblemFamily.GRAPH_TREE,
                GenerationPattern.TREE_DP_SUBTREE_SUM
        ));
        corpus.add(new CorpusItem(
                "Weighted Shortest Path",
                "Given a weighted graph with vertices and edges, find the shortest path from source s to destination t using Dijkstra's algorithm.",
                ProblemFamily.GRAPH_TREE,
                GenerationPattern.GRAPH_SHORTEST_PATH
        ));
        corpus.add(new CorpusItem(
                "Graph Connectivity",
                "Determine if vertices are in the same component using a Disjoint Set Union (DSU) data structure.",
                ProblemFamily.GRAPH_TREE,
                GenerationPattern.GRAPH_DSU_COMPONENTS
        ));
        corpus.add(new CorpusItem(
                "Course Schedule",
                "Given a list of course prerequisites, find a valid topological order topological sort of the DAG.",
                ProblemFamily.GRAPH_TREE,
                GenerationPattern.GRAPH_TOPOLOGICAL_ORDER
        ));
        corpus.add(new CorpusItem(
                "Graph Reachability BFS",
                "Count the number of reachable vertices in a graph using BFS or DFS.",
                ProblemFamily.GRAPH_TREE,
                GenerationPattern.GRAPH_REACHABILITY
        ));

        // --- COMMAND_BASED & GRID ---
        corpus.add(new CorpusItem(
                "Power Tower 3D Commands",
                "Maintain a 3D coordinate system where we query sub-grids and update cells using x y z commands.",
                ProblemFamily.GRID,
                GenerationPattern.POWER_TOWER_3D_COMMANDS
        ));
        corpus.add(new CorpusItem(
                "Danger Detection",
                "A robot moves on an n x n grid from (1, 1) to (n, n). A cell with load-bearing capacity <= G is dangerous. Find the minimum dangerous cells path.",
                ProblemFamily.GRID,
                GenerationPattern.GRID_DANGER_DETECTION
        ));

        // --- RANGE_QUERY_UPDATE ---
        corpus.add(new CorpusItem(
                "Range Sum Queries",
                "Given an array, answer range sum queries to return sum of elements from l to r.",
                ProblemFamily.RANGE_QUERY_UPDATE,
                GenerationPattern.RANGE_SUM_QUERIES
        ));

        // --- DYNAMIC_PROGRAMMING ---
        corpus.add(new CorpusItem(
                "Coin Change Problem",
                "Find the minimum number of coins needed to make a target sum.",
                ProblemFamily.DYNAMIC_PROGRAMMING,
                GenerationPattern.COIN_CHANGE_MIN_COINS
        ));
        corpus.add(new CorpusItem(
                "Classic 0/1 Knapsack",
                "Choose items with given weight and value to fit in a knapsack of capacity C to maximize total value.",
                ProblemFamily.DYNAMIC_PROGRAMMING,
                GenerationPattern.KNAPSACK_01
        ));
        corpus.add(new CorpusItem(
                "Longest Increasing Subsequence",
                "Find the longest increasing subsequence LIS length in an array.",
                ProblemFamily.DYNAMIC_PROGRAMMING,
                GenerationPattern.LIS_LENGTH
            ));
            corpus.add(new CorpusItem(
                    "Bitmask Assignment",
                    "Assign tasks to people using dynamic programming with bitmask to minimize total cost.",
                    ProblemFamily.DYNAMIC_PROGRAMMING,
                    GenerationPattern.BITMASK_ASSIGNMENT
            ));

            // --- NUMBER_THEORY ---
            corpus.add(new CorpusItem(
                    "Fibonacci Power Sum",
                    "Compute the sum of Fibonacci numbers raised to power k modulo 998244353.",
                    ProblemFamily.NUMBER_THEORY,
                    GenerationPattern.FIBONACCI_POWER_SUM
            ));
            corpus.add(new CorpusItem(
                    "Journey to Sequence Sum",
                    "Find the sum of the first n terms where terms are of form i^k * r^i modulo 1000000007.",
                    ProblemFamily.NUMBER_THEORY,
                    GenerationPattern.WEIGHTED_SEQUENCE_SUM
            ));
            corpus.add(new CorpusItem(
                    "Prime Counting",
                    "Find the number of primes / prime count up to N.",
                    ProblemFamily.NUMBER_THEORY,
                    GenerationPattern.PRIME_COUNT
            ));
            corpus.add(new CorpusItem(
                    "Combinatorics Combinations",
                    "Compute binomial coefficients / combination values NCR modulo M.",
                    ProblemFamily.NUMBER_THEORY,
                    GenerationPattern.NCR_MOD
            ));

            // --- DATA_STRUCTURE ---
            corpus.add(new CorpusItem(
                    "Segment Tree Range Sum",
                    "Perform range updates and range sum queries using a segment tree.",
                    ProblemFamily.DATA_STRUCTURE,
                    GenerationPattern.SEGMENT_TREE_RANGE_SUM_UPDATE
            ));
            corpus.add(new CorpusItem(
                    "Fenwick Point Update",
                    "Point update and range sum queries using a fenwick tree.",
                    ProblemFamily.DATA_STRUCTURE,
                    GenerationPattern.FENWICK_POINT_UPDATE_RANGE_SUM
            ));

            // --- STRING ---
            corpus.add(new CorpusItem(
                    "Substring Hashing Equality",
                    "Check rolling hash substring equality for multiple queries.",
                    ProblemFamily.STRING,
                    GenerationPattern.STRING_SUBSTRING_EQUALITY
            ));
            corpus.add(new CorpusItem(
                    "KMP Pattern Occurrences",
                    "Count pattern occurrences in a string using KMP matcher.",
                    ProblemFamily.STRING,
                    GenerationPattern.STRING_KMP_COUNT
            ));

            // --- GENERAL ---
            corpus.add(new CorpusItem(
                    "Binary Search Lower Bound",
                    "Find the first position of x in a sorted array with lower bound binary search.",
                    ProblemFamily.ARRAY,
                    GenerationPattern.BINARY_SEARCH_LOWER_BOUND
            ));
            corpus.add(new CorpusItem(
                    "Harry's Magical Number",
                    "Find the smallest possible integer satisfying divisibility/multiple constraints: A is a divisor, B is not a divisor, C is a multiple, D is not a multiple.",
                    ProblemFamily.GENERAL,
                    GenerationPattern.BINARY_SEARCH_MIN_FEASIBLE
            ));
            corpus.add(new CorpusItem(
                    "Two Pointer Pair Count",
                    "Use two pointers to count the number of pairs summing to less than target.",
                    ProblemFamily.GENERAL,
                    GenerationPattern.TWO_POINTER_PAIR_COUNT
            ));
            corpus.add(new CorpusItem(
                    "Greedy Interval Scheduling",
                    "Select maximum non-overlapping intervals greedily.",
                    ProblemFamily.GENERAL,
                    GenerationPattern.GREEDY_INTERVAL_SCHEDULING
            ));
            corpus.add(new CorpusItem(
                    "Mingle Lineup Inversion Minimization",
                    "Insert elements from group B into a group A array to minimize inversions mistakes.",
                    ProblemFamily.ARRAY,
                    GenerationPattern.INSERTION_INVERSION_MINIMIZATION
            ));

            // --- COMBINATORICS ---
            corpus.add(new CorpusItem(
                    "Exact Permutation Mapping",
                    "Convert lexicographical index to permutation, or permutation to rank unrank.",
                    ProblemFamily.COMBINATORICS,
                    GenerationPattern.PERMUTATION_RANK_UNRANK
            ));
            corpus.add(new CorpusItem(
                    "Intricate Polygons Subset Counting",
                    "Count the number of subsets of sticks that can form valid polygons.",
                    ProblemFamily.COMBINATORICS,
                    GenerationPattern.POLYGON_SUBSET_COUNT
            ));

            // --- CONSTRUCTIVE ---
            corpus.add(new CorpusItem(
                    "Even Odd Permutation Constructive",
                    "Construct a permutation placing even numbers first, then odd numbers.",
                    ProblemFamily.CONSTRUCTIVE,
                    GenerationPattern.CONSTRUCTIVE_EVEN_ODD_PERMUTATION
            ));

            // --- DIGIT_PRODUCT_FACTORIZATION ---
            corpus.add(new CorpusItem(
                    "Smallest Digit Product",
                    "Find the smallest integer with a specific digit product factorization.",
                    ProblemFamily.DIGIT_PRODUCT_FACTORIZATION,
                    GenerationPattern.DIGIT_PRODUCT_FACTORIZATION
            ));

            // --- GAME_THEORY ---
            corpus.add(new CorpusItem(
                    "Rightmost Cow Game",
                    "Play the max-welter cow-game turn-taking on stalls with rightmost cow moves by Hieu and RR.",
                    ProblemFamily.GAME_THEORY,
                    GenerationPattern.MAX_WELTER_COW_GAME
            ));
            corpus.add(new CorpusItem(
                    "Stones Subtraction Pile Game",
                    "Take away 1, 2, or 3 stones from pile game.",
                    ProblemFamily.GAME_THEORY,
                    GenerationPattern.SUBTRACTION_GAME
            ));
            corpus.add(new CorpusItem(
                    "Goals",
                    "Players make choices optimally to maximize the expected value of optimal play score.",
                    ProblemFamily.GAME_THEORY,
                    GenerationPattern.EXPECTED_VALUE_OPTIMAL_PLAY
            ));

            // --- Run Evaluation ---
            int total = corpus.size();
            int correctFamily = 0;
            int correctPattern = 0;
            int generationReady = 0;

            List<Map<String, Object>> details = new ArrayList<>();
            List<String> correctlyClassified = new ArrayList<>();
            List<String> recognizedButUnsupported = new ArrayList<>();
            List<String> unknown = new ArrayList<>();
            List<String> falsePositivePatterns = new ArrayList<>();

            for (CorpusItem item : corpus) {
                ProblemFamily actualFamily = classifier.classify(item.title(), item.description());
                GenerationDecision decision = analyzer.analyze(item.title(), item.description(), actualFamily);
                GenerationPattern actualPattern = decision.pattern();
                boolean actualSupported = V2ProblemGenerationService.isPatternSupported(actualPattern);

                boolean famMatch = (actualFamily == item.expectedFamily());
                boolean patMatch = (actualPattern == item.expectedPattern());
                boolean supportMatch = actualSupported;

                if (famMatch) correctFamily++;
                if (patMatch) correctPattern++;
                if (patMatch && actualSupported) generationReady++;

                // Collect report groups
                String name = item.title() + " (" + item.expectedFamily() + " -> " + item.expectedPattern() + ")";
                if (patMatch) {
                    if (actualSupported) {
                        correctlyClassified.add(name);
                    } else {
                        recognizedButUnsupported.add(name);
                    }
                } else if (actualPattern == GenerationPattern.UNKNOWN) {
                    unknown.add(name);
                } else {
                    falsePositivePatterns.add(name + " [Actual: " + actualPattern + "]");
                }

                details.add(Map.of(
                        "title", item.title(),
                        "expectedFamily", item.expectedFamily().name(),
                        "actualFamily", actualFamily.name(),
                        "expectedPattern", item.expectedPattern().name(),
                        "actualPattern", actualPattern.name(),
                        "familyCorrect", famMatch,
                        "patternCorrect", patMatch,
                        "supported", actualSupported
                ));
            }

            double familyAcc = (double) correctFamily / total;
            double patternAcc = (double) correctPattern / total;
            double genReadiness = (double) generationReady / total;

            // Output JSON Report
            Map<String, Object> reportJson = Map.of(
                    "summary", Map.of(
                            "totalItems", total,
                            "correctFamilies", correctFamily,
                            "familyAccuracy", familyAcc,
                            "correctPatterns", correctPattern,
                            "patternAccuracy", patternAcc,
                            "generationReadyCount", generationReady,
                            "generationReadyAccuracy", genReadiness
                    ),
                    "groups", Map.of(
                            "correctlyClassifiedAndSupported", correctlyClassified,
                            "recognizedButUnsupported", recognizedButUnsupported,
                            "unknown", unknown,
                            "falsePositivePatternSelections", falsePositivePatterns
                    ),
                    "details", details
            );

            File reportFile = new File("docs/v2-evaluation-report.json");
            reportFile.getParentFile().mkdirs();
            mapper.writeValue(reportFile, reportJson);

            // Print Report
            System.out.println("========================================================================");
            System.out.println("                     PBJ V2 EVALUATION REPORT                           ");
            System.out.println("========================================================================");
            System.out.printf("Total items: %d\n", total);
            System.out.printf("Family Classification Accuracy: %.2f%% (%d/%d)\n", familyAcc * 100, correctFamily, total);
            System.out.printf("Pattern Recognition Accuracy:   %.2f%% (%d/%d)\n", patternAcc * 100, correctPattern, total);
            System.out.printf("Backend Generation Readiness:   %.2f%% (%d/%d)\n", genReadiness * 100, generationReady, total);
            System.out.println("------------------------------------------------------------------------");
            System.out.println("CORRECTLY CLASSIFIED & SUPPORTED:");
            correctlyClassified.forEach(s -> System.out.println(" - " + s));
            System.out.println("------------------------------------------------------------------------");
            System.out.println("RECOGNIZED BUT UNSUPPORTED:");
            recognizedButUnsupported.forEach(s -> System.out.println(" - " + s));
            System.out.println("------------------------------------------------------------------------");
            System.out.println("UNKNOWN:");
            unknown.forEach(s -> System.out.println(" - " + s));
            System.out.println("------------------------------------------------------------------------");
            System.out.println("FALSE-POSITIVE PATTERN SELECTIONS:");
            falsePositivePatterns.forEach(s -> System.out.println(" - " + s));
            System.out.println("========================================================================");

            // Assertions to keep regression suite green
            assertThat(familyAcc).isEqualTo(1.0);
            assertThat(patternAcc).isEqualTo(1.0);
        }
    }
