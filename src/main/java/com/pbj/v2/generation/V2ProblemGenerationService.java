package com.pbj.v2.generation;

import com.pbj.entity.Problem;
import com.pbj.repository.ProblemRepository;
import com.pbj.service.GeminiTestGenerationService;
import com.pbj.service.OcrCleanerService;
import com.pbj.service.TestCaseStorageService;
import com.pbj.v2.contract.ContractValidator;
import com.pbj.v2.contract.ContractTestcaseGenerator;
import com.pbj.v2.contract.GeneratedTestCase;
import com.pbj.v2.contract.KnownContracts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class V2ProblemGenerationService {
    private static final Set<GenerationPattern> BACKEND_SUPPORTED_PATTERNS = Set.of(
            GenerationPattern.GCD_PAIR,
            GenerationPattern.MAXIMUM_SUBARRAY,
            GenerationPattern.ARRAY_SUM_OVERFLOW,
            GenerationPattern.TREE_DISTANCE_QUERIES,
            GenerationPattern.TREE_DP_SUBTREE_SUM,
            GenerationPattern.GRAPH_REACHABILITY,
            GenerationPattern.GRAPH_SHORTEST_PATH,
            GenerationPattern.GRAPH_DSU_COMPONENTS,
            GenerationPattern.GRAPH_TOPOLOGICAL_ORDER,
            GenerationPattern.POWER_TOWER_3D_COMMANDS,
            GenerationPattern.RANGE_SUM_QUERIES,
            GenerationPattern.SEGMENT_TREE_RANGE_SUM_UPDATE,
            GenerationPattern.FENWICK_POINT_UPDATE_RANGE_SUM,
            GenerationPattern.GRID_DANGER_DETECTION,
            GenerationPattern.COIN_CHANGE_MIN_COINS,
            GenerationPattern.KNAPSACK_01,
            GenerationPattern.LIS_LENGTH,
            GenerationPattern.BITMASK_ASSIGNMENT,
            GenerationPattern.FIBONACCI_POWER_SUM,
            GenerationPattern.POLYGON_SUBSET_COUNT,
            GenerationPattern.WEIGHTED_SEQUENCE_SUM,
            GenerationPattern.STRING_SUBSTRING_EQUALITY,
            GenerationPattern.BINARY_SEARCH_LOWER_BOUND,
            GenerationPattern.BINARY_SEARCH_MIN_FEASIBLE,
            GenerationPattern.TWO_POINTER_PAIR_COUNT,
            GenerationPattern.GREEDY_INTERVAL_SCHEDULING,
            GenerationPattern.INSERTION_INVERSION_MINIMIZATION,
            GenerationPattern.CONSTRUCTIVE_EVEN_ODD_PERMUTATION,
            GenerationPattern.DIGIT_PRODUCT_FACTORIZATION,
            GenerationPattern.MAX_WELTER_COW_GAME,
            GenerationPattern.SUBTRACTION_GAME,
            GenerationPattern.PERMUTATION_RANK_UNRANK,
            GenerationPattern.STRING_KMP_COUNT
    );

    public static boolean isPatternSupported(GenerationPattern pattern) {
        return BACKEND_SUPPORTED_PATTERNS.contains(pattern);
    }

    private final ProblemRepository problemRepository;
    private final TestCaseStorageService testCaseStorageService;
    private final GeminiTestGenerationService geminiTestGenerationService;
    private final OcrCleanerService ocrCleanerService;
    private final ProblemStructureClassifier problemStructureClassifier;
    private final ProblemPatternAnalyzer problemPatternAnalyzer;

    @Transactional
    public Problem generate(String title, String description, List<String> base64Images) {
        String source = resolveSourceText(description, base64Images);
        ProblemFamily family = problemStructureClassifier.classify(title, source);
        GenerationDecision decision = problemPatternAnalyzer.analyze(title, source, family);

        Problem problem;
        if (decision.pattern() == GenerationPattern.POWER_TOWER_3D_COMMANDS) {
            problem = generateCommandGridProblem(title, source, family);
        } else if (decision.pattern() == GenerationPattern.GRID_DANGER_DETECTION) {
            problem = generateDangerDetectionGridProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.DIGIT_PRODUCT_FACTORIZATION) {
            problem = generateDigitProductFactorizationProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.GCD_PAIR) {
            problem = generateScalarOnlyProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.MAXIMUM_SUBARRAY) {
            problem = generateArrayProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.ARRAY_SUM_OVERFLOW) {
            problem = generateOverflowArraySumProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.TREE_DISTANCE_QUERIES) {
            problem = generateGraphTreeProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.GRAPH_SHORTEST_PATH) {
            problem = generateGraphShortestPathProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.GRAPH_REACHABILITY) {
            problem = generateGraphReachabilityProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.GRAPH_DSU_COMPONENTS) {
            problem = generateGraphDsuProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.GRAPH_TOPOLOGICAL_ORDER) {
            problem = generateGraphToposortProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.TREE_DP_SUBTREE_SUM) {
            problem = generateTreeDpProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.RANGE_SUM_QUERIES) {
            problem = generateRangeSumQueriesProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.COIN_CHANGE_MIN_COINS) {
            problem = generateCoinChangeProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.KNAPSACK_01) {
            problem = generateKnapsackProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.LIS_LENGTH) {
            problem = generateLisProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.BITMASK_ASSIGNMENT) {
            problem = generateBitmaskAssignmentProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.FIBONACCI_POWER_SUM) {
            problem = generateFibonacciPowerProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.POLYGON_SUBSET_COUNT) {
            problem = generatePolygonSubsetProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.WEIGHTED_SEQUENCE_SUM) {
            problem = generateWeightedSequenceSumProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.FENWICK_POINT_UPDATE_RANGE_SUM) {
            problem = generateFenwickProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.SEGMENT_TREE_RANGE_SUM_UPDATE) {
            problem = generateSegmentTreeProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.BINARY_SEARCH_LOWER_BOUND) {
            problem = generateBinarySearchProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.BINARY_SEARCH_MIN_FEASIBLE) {
            problem = generateMinFeasibleProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.TWO_POINTER_PAIR_COUNT) {
            problem = generateTwoPointersProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.GREEDY_INTERVAL_SCHEDULING) {
            problem = generateGreedyIntervalsProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.INSERTION_INVERSION_MINIMIZATION) {
            problem = generateInsertionInversionProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.STRING_SUBSTRING_EQUALITY) {
            problem = generateStringHashProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.CONSTRUCTIVE_EVEN_ODD_PERMUTATION) {
            problem = generateConstructiveProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.MAX_WELTER_COW_GAME) {
            problem = generateMaxWelterGameProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.SUBTRACTION_GAME) {
            problem = generateSubtractionGameProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.PERMUTATION_RANK_UNRANK) {
            problem = generatePermutationRankUnrankProblem(title, source);
        } else if (decision.pattern() == GenerationPattern.STRING_KMP_COUNT) {
            problem = generateStringKmpCountProblem(title, source);
        } else {
            System.err.println("WARN: V2 generation not supported for family " + family + " and pattern " + decision.pattern());
            return null;
        }

        enrichProblemMetadataWithOriginalDetails(problem, source);
        return problem;
    }

    private String resolveSourceText(String description, List<String> base64Images) {
        if (base64Images != null && !base64Images.isEmpty()) {
            try {
                String extracted = geminiTestGenerationService.extractProblemText(description, base64Images);
                String cleaned = ocrCleanerService.clean(extracted, description);
                if (cleaned != null && !cleaned.isBlank()) return cleaned;
            } catch (RuntimeException e) {
                System.err.println("WARN: V2 OCR failed; falling back to user-provided text. Cause: " + e.getMessage());
            }
        }
        return description == null ? "" : description;
    }

    private Problem generateCommandGridProblem(String requestedTitle, String source, ProblemFamily family) {
        Problem problem = new Problem();
        problem.setTitle(requestedTitle == null || requestedTitle.isBlank() ? "Command Grid Problem" : requestedTitle.trim());
        setProblemDescription(problem, source, """
                A tower is represented as an n x n x n grid. Initially every block has power level 0.
                UPDATE x y z W sets the block at (x, y, z) to W.
                QUERY x1 y1 z1 x2 y2 z2 asks for the sum of all block values in the inclusive cuboid.
                """);
        problem.setInputFormat("""
                The first line contains T, the number of test cases.
                Each test case starts with n and m.
                The next m lines are either UPDATE x y z W or QUERY x1 y1 z1 x2 y2 z2.
                """);
        problem.setOutputFormat("For every QUERY operation, print the requested sum on a new line.");
        problem.setConstraints("""
                Generated judge data uses 1 <= T <= 3, 1 <= n <= 50, 1 <= m <= 120.
                Coordinates are 1-indexed. W fits in signed 32-bit range; query sums require 64-bit integers.
                """);
        problem.setTimeLimit(1000);
        problem.setMemoryLimit(256);
        problem.setCreatedAt(LocalDateTime.now());
        problem.setValidatorCode(powerTowerValidatorCode());
        problem.setTestPlan("""
                Contract-first v2 plan:
                - Classified family: %s.
                - Backend owns the input generator and always emits T for multi-test input.
                - Every generated testcase is parsed by the v2 contract validator before output is computed.
                - Outputs are computed by a Java oracle that implements UPDATE as set-assignment.
                - Profiles cover boundary, random small, mixed updates/queries, overflow-oriented values, and stress-like command counts.
                """.formatted(family));
        problem.setAcceptedSolutionLanguage("cpp");
        problem.setAcceptedSolutionCode(powerTowerReferenceSolution());
        Problem saved = problemRepository.save(problem);

        List<GeneratedTestCase> generated = powerTowerInputs();
        int seq = 1;
        for (GeneratedTestCase testCase : generated) {
            String output = solvePowerTower(testCase.input());
            testCaseStorageService.saveTestCase(
                    saved,
                    testCase.input(),
                    output,
                    seq == 1,
                    seq++);
        }
        return saved;
    }

    private List<GeneratedTestCase> powerTowerInputs() {
        // Keep the known contract in play so this path fails if the multi-test shape regresses.
        KnownContracts.powerTower();
        List<GeneratedTestCase> cases = new ArrayList<>();
        cases.add(new GeneratedTestCase("sample_like_boundary", 1, """
                1
                4 7
                QUERY 1 1 1 4 4 4
                UPDATE 1 1 1 5
                QUERY 1 1 1 1 1 1
                UPDATE 1 1 1 -2
                QUERY 1 1 1 1 1 1
                UPDATE 4 4 4 10
                QUERY 1 1 1 4 4 4
                """));
        cases.add(randomPowerTowerCase("random_small", 2, 2, 8, 18, 100, true));
        cases.add(randomPowerTowerCase("random_medium", 3, 3, 20, 60, 1_000_000, true));
        cases.add(randomPowerTowerCase("overwrite_same_cell", 4, 1, 12, 80, 2_000_000_000, false));
        cases.add(randomPowerTowerCase("large_coordinates", 5, 2, 50, 120, 1_000_000_000, true));
        cases.add(new GeneratedTestCase("single_cell_overflow", 6, """
                1
                1 6
                UPDATE 1 1 1 1000000000
                QUERY 1 1 1 1 1 1
                UPDATE 1 1 1 -1000000000
                QUERY 1 1 1 1 1 1
                UPDATE 1 1 1 999999999
                QUERY 1 1 1 1 1 1
                """));
        return cases;
    }

    private GeneratedTestCase randomPowerTowerCase(String profile, int seed, int t, int nMax, int mMax, int absW, boolean mixed) {
        Random random = new Random(seed);
        StringBuilder input = new StringBuilder();
        input.append(t).append('\n');
        for (int caseNo = 0; caseNo < t; caseNo++) {
            int n = 1 + random.nextInt(nMax);
            int m = Math.max(1, mMax - random.nextInt(Math.max(1, mMax / 3)));
            input.append(n).append(' ').append(m).append('\n');
            for (int i = 0; i < m; i++) {
                boolean update = !mixed || i == 0 || random.nextInt(100) < 55;
                if (update) {
                    int x = 1 + random.nextInt(n);
                    int y = 1 + random.nextInt(n);
                    int z = 1 + random.nextInt(n);
                    long wSpan = (long) absW * 2L + 1L;
                    int w = (int) (Math.floorMod(random.nextLong(), wSpan) - absW);
                    input.append("UPDATE ").append(x).append(' ').append(y).append(' ').append(z).append(' ').append(w).append('\n');
                } else {
                    int x1 = 1 + random.nextInt(n);
                    int x2 = x1 + random.nextInt(n - x1 + 1);
                    int y1 = 1 + random.nextInt(n);
                    int y2 = y1 + random.nextInt(n - y1 + 1);
                    int z1 = 1 + random.nextInt(n);
                    int z2 = z1 + random.nextInt(n - z1 + 1);
                    input.append("QUERY ").append(x1).append(' ').append(y1).append(' ').append(z1).append(' ')
                            .append(x2).append(' ').append(y2).append(' ').append(z2).append('\n');
                }
            }
        }
        return new GeneratedTestCase(profile, seed, input.toString());
    }

    private String solvePowerTower(String input) {
        FastScanner fs = new FastScanner(input);
        int t = fs.nextInt();
        StringBuilder out = new StringBuilder();
        for (int tc = 0; tc < t; tc++) {
            int n = fs.nextInt();
            int m = fs.nextInt();
            Map<Long, Long> values = new HashMap<>();
            for (int i = 0; i < m; i++) {
                String cmd = fs.next();
                if ("UPDATE".equals(cmd)) {
                    int x = fs.nextInt();
                    int y = fs.nextInt();
                    int z = fs.nextInt();
                    long w = fs.nextLong();
                    values.put(key(x, y, z), w);
                } else if ("QUERY".equals(cmd)) {
                    int x1 = fs.nextInt();
                    int y1 = fs.nextInt();
                    int z1 = fs.nextInt();
                    int x2 = fs.nextInt();
                    int y2 = fs.nextInt();
                    int z2 = fs.nextInt();
                    long sum = 0;
                    for (Map.Entry<Long, Long> entry : values.entrySet()) {
                        int[] xyz = unkey(entry.getKey());
                        if (x1 <= xyz[0] && xyz[0] <= x2
                                && y1 <= xyz[1] && xyz[1] <= y2
                                && z1 <= xyz[2] && xyz[2] <= z2) {
                            sum += entry.getValue();
                        }
                    }
                    out.append(sum).append('\n');
                } else {
                    throw new IllegalArgumentException("Unknown command in generated input: " + cmd);
                }
            }
        }
        return out.toString();
    }

    private long key(int x, int y, int z) {
        return ((long) x << 40) ^ ((long) y << 20) ^ z;
    }

    private int[] unkey(long key) {
        return new int[] {
                (int) (key >> 40),
                (int) ((key >> 20) & ((1 << 20) - 1)),
                (int) (key & ((1 << 20) - 1))
        };
    }

    private String powerTowerValidatorCode() {
        return """
                import sys
                data = sys.stdin.read().strip().split()
                i = 0
                def die(msg):
                    print(msg, file=sys.stderr)
                    sys.exit(1)
                def nxt(name):
                    global i
                    if i >= len(data): die('missing ' + name)
                    v = data[i]; i += 1; return v
                def num(name):
                    try: return int(nxt(name))
                    except Exception: die(name + ' is not an integer')
                T = num('T')
                if T < 1 or T > 20: die('T out of range')
                for _ in range(T):
                    n = num('n'); m = num('m')
                    if n < 1 or n > 1000000000: die('n out of range')
                    if m < 1 or m > 200000: die('m out of range')
                    for __ in range(m):
                        cmd = nxt('command')
                        if cmd == 'UPDATE':
                            x = num('x'); y = num('y'); z = num('z'); w = num('w')
                            if not (1 <= x <= n and 1 <= y <= n and 1 <= z <= n): die('coordinate out of range')
                            if not (-1000000000 <= w <= 1000000000): die('w out of range')
                        elif cmd == 'QUERY':
                            x1 = num('x1'); y1 = num('y1'); z1 = num('z1')
                            x2 = num('x2'); y2 = num('y2'); z2 = num('z2')
                            if not (1 <= x1 <= x2 <= n and 1 <= y1 <= y2 <= n and 1 <= z1 <= z2 <= n): die('query range invalid')
                        else:
                            die('unknown command')
                if i != len(data): die('extra tokens')
                """;
    }

    private String powerTowerReferenceSolution() {
        return """
                #include <bits/stdc++.h>
                using namespace std;
                struct Fenwick3D {
                    int n;
                    vector<vector<vector<long long>>> bit;
                    Fenwick3D(int n): n(n), bit(n + 1, vector<vector<long long>>(n + 1, vector<long long>(n + 1, 0))) {}
                    void add(int x, int y, int z, long long delta) {
                        for (int i = x; i <= n; i += i & -i)
                            for (int j = y; j <= n; j += j & -j)
                                for (int k = z; k <= n; k += k & -k)
                                    bit[i][j][k] += delta;
                    }
                    long long sum(int x, int y, int z) {
                        long long res = 0;
                        for (int i = x; i > 0; i -= i & -i)
                            for (int j = y; j > 0; j -= j & -j)
                                for (int k = z; k > 0; k -= k & -k)
                                    res += bit[i][j][k];
                        return res;
                    }
                    long long range(int x1, int y1, int z1, int x2, int y2, int z2) {
                        return sum(x2,y2,z2) - sum(x1-1,y2,z2) - sum(x2,y1-1,z2) - sum(x2,y2,z1-1)
                             + sum(x1-1,y1-1,z2) + sum(x1-1,y2,z1-1) + sum(x2,y1-1,z1-1)
                             - sum(x1-1,y1-1,z1-1);
                    }
                };
                int main() {
                    ios::sync_with_stdio(false);
                    cin.tie(nullptr);
                    int T;
                    if (!(cin >> T)) return 0;
                    while (T--) {
                        int n, m;
                        cin >> n >> m;
                        Fenwick3D fw(n);
                        map<tuple<int,int,int>, long long> current;
                        while (m--) {
                            string cmd;
                            cin >> cmd;
                            if (cmd == "UPDATE") {
                                int x,y,z; long long w;
                                cin >> x >> y >> z >> w;
                                auto key = make_tuple(x,y,z);
                                long long old = current[key];
                                current[key] = w;
                                fw.add(x,y,z,w-old);
                            } else {
                                int x1,y1,z1,x2,y2,z2;
                                cin >> x1 >> y1 >> z1 >> x2 >> y2 >> z2;
                                cout << fw.range(x1,y1,z1,x2,y2,z2) << '\\n';
                            }
                        }
                    }
                    return 0;
                }
                """;
    }

    private Problem generateDigitProductFactorizationProblem(String requestedTitle, String source) {
        Problem problem = new Problem();
        problem.setTitle(requestedTitle == null || requestedTitle.isBlank() ? "Digit Product Factorization" : requestedTitle.trim());
        setProblemDescription(problem, source, """
                Given two integers n and k, choose exactly k digits from 1 to 9 such that their product equals n.
                Print the smallest numeric sequence formed by those digits, or -1 if no such sequence exists.
                """);
        problem.setInputFormat("The input contains two integers n and k.");
        problem.setOutputFormat("Print the smallest sequence of exactly k digits whose product is n, or -1.");
        problem.setConstraints("""
                Generated judge data uses 1 <= n <= 1000000000 and 1 <= k <= 10000.
                Digits are restricted to 1..9. The answer is ordered increasingly for the minimum numeric sequence.
                """);
        problem.setTimeLimit(1000);
        problem.setMemoryLimit(256);
        problem.setCreatedAt(LocalDateTime.now());
        problem.setValidatorCode(digitProductValidatorCode());
        problem.setTestPlan("""
                Contract-first v2 plan:
                - Classified family: DIGIT_PRODUCT_FACTORIZATION.
                - Backend generates scalar-only n/k inputs from factorization profiles.
                - Java oracle factors n by digits 9..2, pads with 1, sorts ascending, and emits -1 when impossible.
                - Profiles cover samples, n=1 padding, impossible prime factors, too many required digits, and large composite values.
                """);
        problem.setAcceptedSolutionLanguage("cpp");
        problem.setAcceptedSolutionCode(digitProductReferenceSolution());
        Problem saved = problemRepository.save(problem);

        List<GeneratedTestCase> generated = dynamicDigitProductInputs();
        int seq = 1;
        for (GeneratedTestCase testCase : generated) {
            String output = solveDigitProduct(testCase.input());
            testCaseStorageService.saveTestCase(saved, testCase.input(), output, seq <= 2, seq++);
        }
        return saved;
    }

    private Problem generateDangerDetectionGridProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Danger Detection");
        setProblemDescription(problem, source, """
                A robot moves on an n x n grid from cell (1, 1) to cell (n, n) using adjacent cells.
                Each cell has a positive load-bearing capacity a[i][j]. For a given weight G, a cell with capacity <= G
                is dangerous. Problem 1 asks for the minimum number of dangerous cells on a path. Problem 2 asks for the
                maximum G such that some path contains no dangerous cells.
                """);
        problem.setInputFormat("""
                The first line contains problem type 1 or 2.
                For type 1, the second line contains n and G. For type 2, the second line contains n.
                The next n lines contain n integers each, the grid capacities.
                """);
        problem.setOutputFormat("Print one integer: the answer for the selected problem type.");
        problem.setConstraints("Generated judge data uses 1 <= n <= 8 and 1 <= a[i][j] <= 10000.");
        problem.setValidatorCode("""
                import sys
                data = list(map(int, sys.stdin.read().strip().split()))
                if len(data) < 2: raise SystemExit('missing header')
                typ = data[0]
                if typ not in (1, 2): raise SystemExit('type must be 1 or 2')
                idx = 1
                n = data[idx]; idx += 1
                if not (1 <= n <= 8): raise SystemExit('n out of range')
                if typ == 1:
                    if idx >= len(data): raise SystemExit('missing G')
                    g = data[idx]; idx += 1
                    if not (0 <= g <= 10000): raise SystemExit('G out of range')
                need = n * n
                if len(data) - idx != need: raise SystemExit('wrong matrix size')
                if any(x < 1 or x > 10000 for x in data[idx:]): raise SystemExit('capacity out of range')
                """);
        problem.setTestPlan("""
                Contract-first v2 seed pattern:
                - Classified family: GRID.
                - Inferred pattern: GRID_DANGER_DETECTION.
                - Type 1 uses 0-1 BFS to minimize dangerous cells for a fixed threshold G.
                - Type 2 uses widest-path bottleneck search and returns maximin(path) - 1.
                - Profiles cover all-safe, all-dangerous, forced detours, and mixed bottleneck grids.
                """);
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std;
                int main(){ios::sync_with_stdio(false);cin.tie(nullptr);int typ;if(!(cin>>typ))return 0;int n,g=0;cin>>n;if(typ==1)cin>>g;vector<vector<int>>a(n,vector<int>(n));for(auto&row:a)for(int&x:row)cin>>x;int dx[4]={1,-1,0,0},dy[4]={0,0,1,-1};if(typ==1){const int INF=1e9;deque<pair<int,int>>dq;vector<vector<int>>d(n,vector<int>(n,INF));d[0][0]=(a[0][0]<=g);dq.push_back({0,0});while(!dq.empty()){auto [x,y]=dq.front();dq.pop_front();for(int k=0;k<4;k++){int nx=x+dx[k],ny=y+dy[k];if(nx<0||ny<0||nx>=n||ny>=n)continue;int w=(a[nx][ny]<=g);if(d[nx][ny]>d[x][y]+w){d[nx][ny]=d[x][y]+w;if(w)dq.push_back({nx,ny});else dq.push_front({nx,ny});}}}cout<<d[n-1][n-1]<<'\\n';}else{priority_queue<tuple<int,int,int>>pq;vector<vector<int>>best(n,vector<int>(n,-1));best[0][0]=a[0][0];pq.push({a[0][0],0,0});while(!pq.empty()){auto [cap,x,y]=pq.top();pq.pop();if(cap!=best[x][y])continue;for(int k=0;k<4;k++){int nx=x+dx[k],ny=y+dy[k];if(nx<0||ny<0||nx>=n||ny>=n)continue;int nc=min(cap,a[nx][ny]);if(nc>best[nx][ny]){best[nx][ny]=nc;pq.push({nc,nx,ny});}}}cout<<best[n-1][n-1]-1<<'\\n';}}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = dynamicDangerDetectionGridInputs();
        saveGeneratedCases(saved, cases, this::solveDangerDetectionGrid, 2);
        return saved;
    }

    private List<GeneratedTestCase> digitProductInputs() {
        return List.of(
                new GeneratedTestCase("sample_possible", 1, "12 2\n"),
                new GeneratedTestCase("sample_impossible", 2, "34 2\n"),
                new GeneratedTestCase("one_padding", 3, "1 5\n"),
                new GeneratedTestCase("too_many_factor_digits", 4, "512 2\n"),
                new GeneratedTestCase("large_composite", 5, "1000000000 20\n"),
                new GeneratedTestCase("prime_factor", 6, "999999937 4\n"),
                new GeneratedTestCase("minimum_digits_with_padding", 7, "72 5\n"),
                new GeneratedTestCase("exact_single_digit", 8, "9 1\n")
        );
    }

    private List<GeneratedTestCase> dynamicDangerDetectionGridInputs() {
        List<GeneratedTestCase> cases = new ArrayList<>();
        Random random = new Random(42);
        // Case 1: Type 1, n=4, G=5
        StringBuilder c1 = new StringBuilder("1\n4 5\n");
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                c1.append(1 + random.nextInt(10)).append(j == 3 ? "" : " ");
            }
            c1.append("\n");
        }
        cases.add(new GeneratedTestCase("type1_dynamic_small", 1, c1.toString()));

        // Case 2: Type 2, n=5
        StringBuilder c2 = new StringBuilder("2\n5\n");
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                c2.append(1 + random.nextInt(20)).append(j == 4 ? "" : " ");
            }
            c2.append("\n");
        }
        cases.add(new GeneratedTestCase("type2_dynamic_medium", 2, c2.toString()));

        // Case 3: Type 1, n=6, G=8 (larger boundary)
        StringBuilder c3 = new StringBuilder("1\n6 8\n");
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 6; j++) {
                c3.append(1 + random.nextInt(15)).append(j == 5 ? "" : " ");
            }
            c3.append("\n");
        }
        cases.add(new GeneratedTestCase("type1_dynamic_large", 3, c3.toString()));

        // Case 4: Type 2, n=8 (maximum constraint)
        StringBuilder c4 = new StringBuilder("2\n8\n");
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                c4.append(1 + random.nextInt(100)).append(j == 7 ? "" : " ");
            }
            c4.append("\n");
        }
        cases.add(new GeneratedTestCase("type2_dynamic_max", 4, c4.toString()));

        return cases;
    }

    private List<GeneratedTestCase> dynamicDigitProductInputs() {
        List<GeneratedTestCase> cases = new ArrayList<>();
        Random random = new Random(42);
        cases.add(new GeneratedTestCase("sample_possible", 1, "12 2\n"));
        cases.add(new GeneratedTestCase("sample_impossible", 2, "34 2\n"));
        cases.add(new GeneratedTestCase("one_padding", 3, "1 5\n"));
        cases.add(new GeneratedTestCase("dynamic_prime", 4, "999999937 4\n"));
        long comp = 1;
        for (int i = 0; i < 8; i++) comp *= (2 + random.nextInt(8));
        cases.add(new GeneratedTestCase("dynamic_composite", 5, comp + " 10\n"));
        cases.add(new GeneratedTestCase("dynamic_single", 6, (2 + random.nextInt(8)) + " 1\n"));
        cases.add(new GeneratedTestCase("dynamic_too_many", 7, "1048576 3\n"));
        long compLarge = 1;
        for (int i = 0; i < 15; i++) compLarge *= (2 + random.nextInt(8));
        cases.add(new GeneratedTestCase("dynamic_large_composite", 8, Math.min(1000000000L, compLarge) + " 20\n"));
        return cases;
    }

    private String solveDigitProduct(String input) {
        FastScanner fs = new FastScanner(input);
        long n = fs.nextLong();
        int k = fs.nextInt();
        List<Integer> digits = new ArrayList<>();
        if (n == 1) {
            while (digits.size() < k) digits.add(1);
            return digitsToString(digits) + "\n";
        }
        for (int d = 9; d >= 2; d--) {
            while (n % d == 0) {
                digits.add(d);
                n /= d;
            }
        }
        if (n != 1 || digits.size() > k) {
            return "-1\n";
        }
        while (digits.size() < k) digits.add(1);
        digits.sort(Integer::compareTo);
        return digitsToString(digits) + "\n";
    }

    private String solveDangerDetectionGrid(String input) {
        FastScanner fs = new FastScanner(input);
        int type = fs.nextInt();
        int n = fs.nextInt();
        int g = type == 1 ? fs.nextInt() : 0;
        int[][] a = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                a[i][j] = fs.nextInt();
            }
        }
        int[] dx = {1, -1, 0, 0};
        int[] dy = {0, 0, 1, -1};
        if (type == 1) {
            int inf = 1_000_000_000;
            int[][] dist = new int[n][n];
            for (int[] row : dist) java.util.Arrays.fill(row, inf);
            java.util.ArrayDeque<int[]> deque = new java.util.ArrayDeque<>();
            dist[0][0] = a[0][0] <= g ? 1 : 0;
            deque.add(new int[] {0, 0});
            while (!deque.isEmpty()) {
                int[] cur = deque.removeFirst();
                for (int k = 0; k < 4; k++) {
                    int nx = cur[0] + dx[k];
                    int ny = cur[1] + dy[k];
                    if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue;
                    int w = a[nx][ny] <= g ? 1 : 0;
                    if (dist[nx][ny] > dist[cur[0]][cur[1]] + w) {
                        dist[nx][ny] = dist[cur[0]][cur[1]] + w;
                        if (w == 0) deque.addFirst(new int[] {nx, ny});
                        else deque.addLast(new int[] {nx, ny});
                    }
                }
            }
            return dist[n - 1][n - 1] + "\n";
        }
        int[][] best = new int[n][n];
        for (int[] row : best) java.util.Arrays.fill(row, -1);
        java.util.PriorityQueue<int[]> pq = new java.util.PriorityQueue<>((l, r) -> Integer.compare(r[0], l[0]));
        best[0][0] = a[0][0];
        pq.add(new int[] {a[0][0], 0, 0});
        while (!pq.isEmpty()) {
            int[] cur = pq.remove();
            if (cur[0] != best[cur[1]][cur[2]]) continue;
            for (int k = 0; k < 4; k++) {
                int nx = cur[1] + dx[k];
                int ny = cur[2] + dy[k];
                if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue;
                int next = Math.min(cur[0], a[nx][ny]);
                if (next > best[nx][ny]) {
                    best[nx][ny] = next;
                    pq.add(new int[] {next, nx, ny});
                }
            }
        }
        return (best[n - 1][n - 1] - 1) + "\n";
    }

    private String digitsToString(List<Integer> digits) {
        StringBuilder sb = new StringBuilder();
        for (int d : digits) sb.append(d);
        return sb.toString();
    }

    private String digitProductValidatorCode() {
        return """
                import sys
                data = sys.stdin.read().strip().split()
                if len(data) != 2:
                    print('expected exactly n and k', file=sys.stderr)
                    sys.exit(1)
                try:
                    n = int(data[0]); k = int(data[1])
                except Exception:
                    print('n and k must be integers', file=sys.stderr)
                    sys.exit(1)
                if not (1 <= n <= 1000000000):
                    print('n out of range', file=sys.stderr)
                    sys.exit(1)
                if not (1 <= k <= 10000):
                    print('k out of range', file=sys.stderr)
                    sys.exit(1)
                """;
    }

    private String digitProductReferenceSolution() {
        return """
                #include <bits/stdc++.h>
                using namespace std;
                int main() {
                    ios::sync_with_stdio(false);
                    cin.tie(nullptr);
                    long long n;
                    int k;
                    if (!(cin >> n >> k)) return 0;
                    vector<int> digits;
                    if (n == 1) {
                        while ((int)digits.size() < k) digits.push_back(1);
                    } else {
                        for (int d = 9; d >= 2; --d) {
                            while (n % d == 0) {
                                digits.push_back(d);
                                n /= d;
                            }
                        }
                        if (n != 1 || (int)digits.size() > k) {
                            cout << -1 << '\\n';
                            return 0;
                        }
                        while ((int)digits.size() < k) digits.push_back(1);
                        sort(digits.begin(), digits.end());
                    }
                    for (int d : digits) cout << d;
                    cout << '\\n';
                    return 0;
                }
                """;
    }

    private Problem generateScalarOnlyProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Greatest Common Divisor");
        setProblemDescription(problem, source, "Given two positive integers a and b, print their greatest common divisor.");
        problem.setInputFormat("The input contains two integers a and b.");
        problem.setOutputFormat("Print gcd(a, b).");
        problem.setConstraints("Generated judge data uses 1 <= a, b <= 1000000000.");
        problem.setValidatorCode("""
                import sys, math
                data = sys.stdin.read().strip().split()
                if len(data) != 2: raise SystemExit('expected a and b')
                a, b = map(int, data)
                if not (1 <= a <= 1000000000 and 1 <= b <= 1000000000): raise SystemExit('out of range')
                """);
        problem.setTestPlan("""
                Contract-first v2 plan:
                - Classified family: SCALAR_ONLY.
                - Backend emits scalar-only a/b inputs and validates them against the scalar contract.
                - Java oracle uses Euclid's algorithm.
                """);
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std;
                int main(){ long long a,b; if(!(cin>>a>>b)) return 0; cout<<gcd(a,b)<<'\\n'; }
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("coprime", 1, "17 31\n"),
                new GeneratedTestCase("equal", 2, "999999937 999999937\n"),
                new GeneratedTestCase("shared_factor", 3, "864197532 12345678\n"),
                new GeneratedTestCase("unit", 4, "1 1000000000\n")
        );
        saveGeneratedCases(saved, KnownContracts.gcdPair(), cases, this::solveGcdPair, 2);
        return saved;
    }

    private Problem generateArrayProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Maximum Subarray Sum");
        setProblemDescription(problem, source, "Given an integer array, print the maximum sum of a non-empty contiguous subarray.");
        problem.setInputFormat("The first line contains n. The second line contains n integers.");
        problem.setOutputFormat("Print the maximum subarray sum.");
        problem.setConstraints("Generated judge data uses 1 <= n <= 200 and -1000000000 <= a_i <= 1000000000.");
        problem.setValidatorCode("""
                import sys
                data = list(map(int, sys.stdin.read().strip().split()))
                if not data: raise SystemExit('missing n')
                n = data[0]
                if not (1 <= n <= 200): raise SystemExit('n out of range')
                if len(data) != n + 1: raise SystemExit('wrong array length')
                if any(x < -1000000000 or x > 1000000000 for x in data[1:]): raise SystemExit('a_i out of range')
                """);
        problem.setTestPlan("""
                Contract-first v2 plan:
                - Classified family: ARRAY.
                - Backend emits n plus one validated array section.
                - Java oracle runs Kadane's algorithm and keeps all-negative cases in coverage.
                """);
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std;
                int main(){ios::sync_with_stdio(false);cin.tie(nullptr);int n;if(!(cin>>n))return 0;long long best=LLONG_MIN,cur=LLONG_MIN;for(int i=0;i<n;i++){long long x;cin>>x;cur=(i?max(x,cur+x):x);best=max(best,cur);}cout<<best<<'\\n';}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("mixed", 1, "8\n-2 1 -3 4 -1 2 1 -5\n"),
                new GeneratedTestCase("all_negative", 2, "5\n-8 -3 -6 -2 -5\n"),
                new GeneratedTestCase("single", 3, "1\n42\n"),
                new GeneratedTestCase("overflow_guard", 4, "4\n1000000000 1000000000 -1 1000000000\n")
        );
        saveGeneratedCases(saved, KnownContracts.maximumSubarray(), cases, this::solveMaximumSubarray, 2);
        return saved;
    }

    private Problem generateOverflowArraySumProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Array Sum 64-bit");
        setProblemDescription(problem, source, "Given n integers, print their total sum. The answer may exceed 32-bit signed range.");
        problem.setInputFormat("The first line contains n. The second line contains n integers.");
        problem.setOutputFormat("Print the 64-bit sum.");
        problem.setConstraints("Generated judge data uses 1 <= n <= 200 and -1000000000 <= a_i <= 1000000000.");
        problem.setValidatorCode("""
                import sys
                data=list(map(int,sys.stdin.read().strip().split()))
                if len(data)<2: raise SystemExit('missing header')
                n=data[0]
                if not(1<=n<=100) or len(data)!=n+2: raise SystemExit('bad shape')
                """);
        problem.setTestPlan("""
                Contract-first v2 seed pattern:
                - Classified family: NUMERIC_OVERFLOW_STRESS.
                - Inferred pattern: ARRAY_SUM_OVERFLOW.
                - Backend cases deliberately exceed 32-bit accumulator range.
                """);
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std;
                int main(){int n;if(!(cin>>n))return 0;long long s=0,x;while(n--){cin>>x;s+=x;}cout<<s<<'\\n';}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("int32_overflow_positive", 1, "4\n1000000000 1000000000 1000000000 1000000000\n"),
                new GeneratedTestCase("mixed_sign", 2, "5\n1000000000 -1000000000 1000000000 -7 9\n"),
                new GeneratedTestCase("negative_overflow", 3, "3\n-1000000000 -1000000000 -1000000000\n")
        );
        saveGeneratedCases(saved, KnownContracts.arraySumOverflow(), cases, this::solveArraySum, 1);
        return saved;
    }

    private Problem generateGraphTreeProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Tree Distance Queries");
        setProblemDescription(problem, source, "Given a tree and node pairs, print the number of edges on the unique path between each pair.");
        problem.setInputFormat("The first line contains n, q, e where e=n-1. The next e lines are edges. The next q lines are queries u v.");
        problem.setOutputFormat("For each query, print the tree distance.");
        problem.setConstraints("Generated judge data uses 2 <= n <= 60 and 1 <= q <= 80.");
        problem.setValidatorCode("""
                import sys
                data = list(map(int, sys.stdin.read().strip().split()))
                if len(data) < 3: raise SystemExit('missing header')
                n, q, e = data[:3]
                if not (2 <= n <= 60 and 1 <= q <= 80 and e == n - 1): raise SystemExit('invalid header')
                if len(data) != 3 + 2 * e + 2 * q: raise SystemExit('wrong token count')
                """);
        problem.setTestPlan("""
                Contract-first v2 plan:
                - Classified family: GRAPH_TREE.
                - Backend emits tree edge rows plus query rows and validates the row-shaped contract.
                - Java oracle answers each query with BFS on the generated tree.
                """);
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std;
                int main(){ios::sync_with_stdio(false);cin.tie(nullptr);int n,q,e;if(!(cin>>n>>q>>e))return 0;vector<vector<int>> g(n+1);for(int i=0,u,v;i<e;i++){cin>>u>>v;g[u].push_back(v);g[v].push_back(u);}while(q--){int s,t;cin>>s>>t;vector<int>d(n+1,-1);queue<int> qu;qu.push(s);d[s]=0;while(!qu.empty()){int u=qu.front();qu.pop();for(int v:g[u])if(d[v]<0){d[v]=d[u]+1;qu.push(v);}}cout<<d[t]<<'\\n';}}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("line_tree", 1, "5 3 4\n1 2\n2 3\n3 4\n4 5\n1 5\n2 4\n3 3\n"),
                new GeneratedTestCase("star_tree", 2, "6 4 5\n1 2\n1 3\n1 4\n1 5\n1 6\n2 3\n4 6\n1 5\n2 2\n"),
                new GeneratedTestCase("branching", 3, "7 4 6\n1 2\n1 3\n2 4\n2 5\n3 6\n6 7\n4 5\n4 7\n3 7\n5 6\n")
        );
        saveGeneratedCases(saved, KnownContracts.treeDistanceQueries(), cases, this::solveTreeDistances, 1);
        return saved;
    }

    private Problem generateGraphShortestPathProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Graph Shortest Path");
        setProblemDescription(problem, source, "Given a weighted undirected graph, find the shortest distance from s to t.");
        problem.setInputFormat("The first line contains n, m, s, t. The next m lines contain u, v, w.");
        problem.setOutputFormat("Print the shortest-path distance.");
        problem.setConstraints("Generated judge data uses 2 <= n <= 30, 1 <= m <= 80, and 1 <= w <= 1000.");
        problem.setValidatorCode("""
                import sys
                data=list(map(int,sys.stdin.read().strip().split()))
                if len(data)<4: raise SystemExit('missing header')
                n,m,s,t=data[:4]
                if not(2<=n<=30 and 1<=m<=80 and 1<=s<=n and 1<=t<=n): raise SystemExit('bad header')
                if len(data)!=4+3*m: raise SystemExit('wrong edge count')
                """);
        problem.setTestPlan("""
                Contract-first v2 seed pattern:
                - Classified family: GRAPH_TREE.
                - Inferred pattern: GRAPH_SHORTEST_PATH.
                - Java oracle uses Dijkstra on small connected weighted graphs.
                """);
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std;
                int main(){int n,m,s,t;if(!(cin>>n>>m>>s>>t))return 0;vector<vector<pair<int,int>>>g(n+1);for(int i=0,u,v,w;i<m;i++){cin>>u>>v>>w;g[u].push_back({v,w});g[v].push_back({u,w});}const long long INF=4e18;vector<long long>d(n+1,INF);priority_queue<pair<long long,int>,vector<pair<long long,int>>,greater<pair<long long,int>>>pq;d[s]=0;pq.push({0,s});while(!pq.empty()){auto [du,u]=pq.top();pq.pop();if(du!=d[u])continue;for(auto [v,w]:g[u])if(d[v]>du+w){d[v]=du+w;pq.push({d[v],v});}}cout<<d[t]<<'\\n';}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("diamond", 1, "4 5 1 4\n1 2 5\n1 3 2\n3 2 1\n2 4 2\n3 4 10\n"),
                new GeneratedTestCase("detour", 2, "5 6 1 5\n1 2 100\n1 3 1\n3 4 1\n4 5 1\n2 5 1\n3 5 10\n")
        );
        saveGeneratedCases(saved, KnownContracts.graphShortestPath(), cases, this::solveGraphShortestPath, 1);
        return saved;
    }

    private Problem generateGraphReachabilityProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Graph Reachability");
        setProblemDescription(problem, source, "Given an undirected graph and a source vertex s, count how many vertices are reachable from s.");
        problem.setInputFormat("The first line contains n, m, s. The next m lines contain undirected edges u v.");
        problem.setOutputFormat("Print the reachable vertex count.");
        problem.setConstraints("Generated judge data uses 2 <= n <= 30 and 1 <= m <= 80.");
        problem.setValidatorCode(simpleUnweightedGraphValidatorCode("s"));
        problem.setTestPlan("""
                Contract-first v2 seed pattern:
                - Classified family: GRAPH_TREE.
                - Inferred pattern: GRAPH_REACHABILITY.
                - Java oracle runs BFS over an undirected graph.
                """);
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std;
                int main(){int n,m,s;if(!(cin>>n>>m>>s))return 0;vector<vector<int>>g(n+1);for(int i=0,u,v;i<m;i++){cin>>u>>v;g[u].push_back(v);g[v].push_back(u);}vector<int>vis(n+1);queue<int>q;q.push(s);vis[s]=1;int ans=0;while(!q.empty()){int u=q.front();q.pop();ans++;for(int v:g[u])if(!vis[v])vis[v]=1,q.push(v);}cout<<ans<<'\\n';}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("connected", 1, "5 4 1\n1 2\n2 3\n3 4\n4 5\n"),
                new GeneratedTestCase("disconnected", 2, "6 3 1\n1 2\n2 3\n5 6\n")
        );
        saveGeneratedCases(saved, KnownContracts.graphReachability(), cases, this::solveGraphReachability, 1);
        return saved;
    }

    private Problem generateGraphDsuProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Connected Components");
        setProblemDescription(problem, source, "Given an undirected graph, compute the number of connected components.");
        problem.setInputFormat("The first line contains n and m. The next m lines contain undirected edges u v.");
        problem.setOutputFormat("Print the number of connected components.");
        problem.setConstraints("Generated judge data uses 2 <= n <= 30 and 0 <= m <= 80.");
        problem.setValidatorCode("""
                import sys
                data=list(map(int,sys.stdin.read().strip().split()))
                if len(data)<2: raise SystemExit('missing header')
                n,m=data[:2]
                if not(2<=n<=30 and 0<=m<=80): raise SystemExit('bad header')
                if len(data)!=2+2*m: raise SystemExit('wrong edge count')
                """);
        problem.setTestPlan("""
                Contract-first v2 seed pattern:
                - Classified family: GRAPH_TREE.
                - Inferred pattern: GRAPH_DSU_COMPONENTS.
                - Java oracle uses union-find to count components.
                """);
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std;
                struct DSU{vector<int>p;DSU(int n):p(n+1){iota(p.begin(),p.end(),0);}int f(int x){return p[x]==x?x:p[x]=f(p[x]);}void u(int a,int b){a=f(a);b=f(b);if(a!=b)p[a]=b;}};
                int main(){int n,m;if(!(cin>>n>>m))return 0;DSU d(n);for(int i=0,u,v;i<m;i++){cin>>u>>v;d.u(u,v);}set<int>s;for(int i=1;i<=n;i++)s.insert(d.f(i));cout<<s.size()<<'\\n';}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("three_components", 1, "6 3\n1 2\n2 3\n5 6\n"),
                new GeneratedTestCase("isolated_only", 2, "4 0\n")
        );
        saveGeneratedCases(saved, KnownContracts.graphDsuComponents(), cases, this::solveGraphDsuComponents, 1);
        return saved;
    }

    private Problem generateGraphToposortProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Topological Order");
        setProblemDescription(problem, source, "Given a DAG, print the lexicographically smallest topological ordering.");
        problem.setInputFormat("The first line contains n and m. The next m lines contain directed edges u v.");
        problem.setOutputFormat("Print one valid lexicographically smallest topological order.");
        problem.setConstraints("Generated judge data uses 2 <= n <= 30 and 1 <= m <= 80.");
        problem.setValidatorCode("""
                import sys
                data=list(map(int,sys.stdin.read().strip().split()))
                if len(data)<2: raise SystemExit('missing header')
                n,m=data[:2]
                if not(2<=n<=30 and 1<=m<=80): raise SystemExit('bad header')
                if len(data)!=2+2*m: raise SystemExit('wrong edge count')
                """);
        problem.setTestPlan("""
                Contract-first v2 seed pattern:
                - Classified family: GRAPH_TREE.
                - Inferred pattern: GRAPH_TOPOLOGICAL_ORDER.
                - Java oracle uses Kahn's algorithm with a min-heap.
                """);
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std;
                int main(){int n,m;if(!(cin>>n>>m))return 0;vector<vector<int>>g(n+1);vector<int>in(n+1);for(int i=0,u,v;i<m;i++){cin>>u>>v;g[u].push_back(v);in[v]++;}priority_queue<int,vector<int>,greater<int>>pq;for(int i=1;i<=n;i++)if(!in[i])pq.push(i);bool first=true;while(!pq.empty()){int u=pq.top();pq.pop();if(!first)cout<<' ';first=false;cout<<u;for(int v:g[u])if(--in[v]==0)pq.push(v);}cout<<'\\n';}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("diamond_dag", 1, "4 4\n1 2\n1 3\n2 4\n3 4\n"),
                new GeneratedTestCase("multiple_sources", 2, "5 4\n1 3\n2 3\n3 4\n2 5\n")
        );
        saveGeneratedCases(saved, KnownContracts.graphTopologicalOrder(), cases, this::solveGraphToposort, 1);
        return saved;
    }

    private Problem generateTreeDpProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Tree Subtree Sum");
        setProblemDescription(problem, source, "Given a rooted tree with values, compute the sum of values in every subtree rooted at 1.");
        problem.setInputFormat("The first line contains n and e=n-1. The second line contains n values. The next e lines contain tree edges.");
        problem.setOutputFormat("Print n subtree sums.");
        problem.setConstraints("Generated judge data uses 2 <= n <= 30.");
        problem.setValidatorCode("""
                import sys
                data=list(map(int,sys.stdin.read().strip().split()))
                if len(data)<2: raise SystemExit('missing header')
                n,e=data[:2]
                if not(2<=n<=30 and e==n-1): raise SystemExit('bad header')
                if len(data)!=2+n+2*e: raise SystemExit('bad shape')
                """);
        problem.setTestPlan("Seed pattern: TREE_DP_SUBTREE_SUM.");
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std; vector<vector<int>>g; vector<long long>a,sub; void dfs(int u,int p){sub[u]=a[u];for(int v:g[u])if(v!=p){dfs(v,u);sub[u]+=sub[v];}}
                int main(){int n,e;if(!(cin>>n>>e))return 0;g.assign(n+1,{});a.assign(n+1,0);sub.assign(n+1,0);for(int i=1;i<=n;i++)cin>>a[i];for(int i=0,u,v;i<e;i++){cin>>u>>v;g[u].push_back(v);g[v].push_back(u);}dfs(1,0);for(int i=1;i<=n;i++){if(i>1)cout<<' ';cout<<sub[i];}cout<<'\\n';}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("branching", 1, "5 4\n1 2 3 4 5\n1 2\n1 3\n2 4\n2 5\n")
        );
        saveGeneratedCases(saved, KnownContracts.treeDpSubtreeSum(), cases, this::solveTreeDp, 1);
        return saved;
    }

    private Problem generateRangeSumQueriesProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Range Sum Queries");
        setProblemDescription(problem, source, "Given an array and q queries [l, r], print the sum on each range.");
        problem.setInputFormat("The first line contains n and q. The second line contains n integers. The next q lines contain l and r.");
        problem.setOutputFormat("Print one sum per query.");
        problem.setConstraints("Generated judge data uses 1 <= n, q <= 100.");
        problem.setValidatorCode("""
                import sys
                data=list(map(int,sys.stdin.read().strip().split()))
                if len(data)<2: raise SystemExit('missing header')
                n,q=data[:2]
                if not(1<=n<=100 and 1<=q<=100): raise SystemExit('bad header')
                if len(data)!=2+n+2*q: raise SystemExit('wrong shape')
                idx=2+n
                for _ in range(q):
                    l,r=data[idx],data[idx+1]; idx+=2
                    if not(1<=l<=r<=n): raise SystemExit('bad range')
                """);
        problem.setTestPlan("""
                Contract-first v2 seed pattern:
                - Classified family: RANGE_QUERY_UPDATE.
                - Inferred pattern: RANGE_SUM_QUERIES.
                - Java oracle uses prefix sums.
                """);
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std;
                int main(){int n,q;if(!(cin>>n>>q))return 0;vector<long long>p(n+1);for(int i=1;i<=n;i++){cin>>p[i];p[i]+=p[i-1];}while(q--){int l,r;cin>>l>>r;cout<<p[r]-p[l-1]<<'\\n';}}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("basic", 1, "5 4\n2 -1 3 7 4\n1 1\n1 5\n2 4\n5 5\n"),
                new GeneratedTestCase("overflow_ranges", 2, "4 3\n1000000000 1000000000 1000000000 1000000000\n1 4\n2 3\n4 4\n")
        );
        saveGeneratedCases(saved, KnownContracts.rangeSumQueries(), cases, this::solveRangeSums, 1);
        return saved;
    }

    private Problem generateCoinChangeProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Coin Change");
        setProblemDescription(problem, source, "Given coin denominations and a target sum, find the minimum number of coins needed, or -1 if impossible.");
        problem.setInputFormat("The first line contains n and target. The second line contains n coin denominations.");
        problem.setOutputFormat("Print the minimum number of coins, or -1.");
        problem.setConstraints("Generated judge data uses 1 <= n <= 20 and 0 <= target <= 200.");
        problem.setValidatorCode("""
                import sys
                data=list(map(int,sys.stdin.read().strip().split()))
                if len(data)<2: raise SystemExit('missing header')
                n,target=data[:2]
                if not(1<=n<=20 and 0<=target<=200): raise SystemExit('bad header')
                if len(data)!=2+n: raise SystemExit('wrong coin count')
                """);
        problem.setTestPlan("""
                Contract-first v2 seed pattern:
                - Classified family: DYNAMIC_PROGRAMMING.
                - Inferred pattern: COIN_CHANGE_MIN_COINS.
                - Java oracle uses bottom-up DP.
                """);
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std;
                int main(){int n,t;if(!(cin>>n>>t))return 0;vector<int>c(n),dp(t+1,1e9);for(int&i:c)cin>>i;dp[0]=0;for(int x=1;x<=t;x++)for(int v:c)if(x>=v)dp[x]=min(dp[x],dp[x-v]+1);cout<<(dp[t]>=1e9?-1:dp[t])<<'\\n';}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("reachable", 1, "3 11\n1 5 7\n"),
                new GeneratedTestCase("unreachable", 2, "2 7\n4 6\n"),
                new GeneratedTestCase("zero_target", 3, "4 0\n2 3 5 9\n")
        );
        saveGeneratedCases(saved, KnownContracts.coinChangeMinCoins(), cases, this::solveCoinChange, 1);
        return saved;
    }

    private Problem generateKnapsackProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "0/1 Knapsack");
        setProblemDescription(problem, source, "Given item weights and values plus a capacity, choose a subset with maximum total value.");
        problem.setInputFormat("The first line contains n and capacity. The next n lines contain weight and value.");
        problem.setOutputFormat("Print the maximum achievable value.");
        problem.setConstraints("Generated judge data uses 1 <= n <= 20 and 1 <= capacity <= 100.");
        problem.setValidatorCode("""
                import sys
                data=list(map(int,sys.stdin.read().strip().split()))
                if len(data)<2: raise SystemExit('missing header')
                n,c=data[:2]
                if not(1<=n<=20 and 1<=c<=100): raise SystemExit('bad header')
                if len(data)!=2+2*n: raise SystemExit('wrong item count')
                """);
        problem.setTestPlan("""
                Contract-first v2 seed pattern:
                - Classified family: DYNAMIC_PROGRAMMING.
                - Inferred pattern: KNAPSACK_01.
                - Java oracle uses standard capacity DP.
                """);
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std;
                int main(){int n,c;if(!(cin>>n>>c))return 0;vector<int>dp(c+1);for(int i=0,w,v;i<n;i++){cin>>w>>v;for(int cap=c;cap>=w;cap--)dp[cap]=max(dp[cap],dp[cap-w]+v);}cout<<dp[c]<<'\\n';}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("classic", 1, "4 7\n6 13\n4 8\n3 6\n5 12\n"),
                new GeneratedTestCase("skip_heavy", 2, "3 5\n6 100\n2 4\n3 5\n")
        );
        saveGeneratedCases(saved, KnownContracts.knapsack01(), cases, this::solveKnapsack, 1);
        return saved;
    }

    private Problem generateLisProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Longest Increasing Subsequence");
        setProblemDescription(problem, source, "Given an array, print the length of its longest strictly increasing subsequence.");
        problem.setInputFormat("The first line contains n. The second line contains n integers.");
        problem.setOutputFormat("Print the LIS length.");
        problem.setConstraints("Generated judge data uses 1 <= n <= 100.");
        problem.setValidatorCode("""
                import sys
                data=list(map(int,sys.stdin.read().strip().split()))
                if len(data)<2: raise SystemExit('missing header')
                n=data[0]
                if not(1<=n<=100) or len(data)!=n+2: raise SystemExit('bad shape')
                """);
        problem.setTestPlan("""
                Contract-first v2 seed pattern:
                - Classified family: DYNAMIC_PROGRAMMING.
                - Inferred pattern: LIS_LENGTH.
                - Java oracle uses O(n^2) DP on small seed cases.
                """);
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std;
                int main(){int n;if(!(cin>>n))return 0;vector<long long>a(n);for(auto&x:a)cin>>x;vector<int>d(n,1);int ans=0;for(int i=0;i<n;i++){for(int j=0;j<i;j++)if(a[j]<a[i])d[i]=max(d[i],d[j]+1);ans=max(ans,d[i]);}cout<<ans<<'\\n';}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("mixed", 1, "8\n10 9 2 5 3 7 101 18\n"),
                new GeneratedTestCase("duplicates", 2, "6\n2 2 2 2 2 2\n"),
                new GeneratedTestCase("descending", 3, "5\n5 4 3 2 1\n")
        );
        saveGeneratedCases(saved, KnownContracts.lisLength(), cases, this::solveLisLength, 1);
        return saved;
    }

    private Problem generateBitmaskAssignmentProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Assignment DP");
        setProblemDescription(problem, source, "Assign each of n workers to a distinct job with minimum total cost.");
        problem.setInputFormat("The first line contains n. The next n lines contain 8 costs; only the first n jobs are used.");
        problem.setOutputFormat("Print the minimum assignment cost.");
        problem.setConstraints("Generated judge data uses 1 <= n <= 8 and 0 <= cost <= 100.");
        problem.setValidatorCode("""
                import sys
                data=list(map(int,sys.stdin.read().strip().split()))
                if not data: raise SystemExit('missing n')
                n=data[0]
                if not(1<=n<=8): raise SystemExit('n out of range')
                if len(data)!=1+8*n: raise SystemExit('wrong matrix size')
                """);
        problem.setTestPlan("""
                Contract-first v2 seed pattern:
                - Classified family: DYNAMIC_PROGRAMMING.
                - Inferred pattern: BITMASK_ASSIGNMENT.
                - Java oracle uses bitmask DP over subsets.
                """);
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std;
                int main(){int n;if(!(cin>>n))return 0;vector<vector<int>>c(n,vector<int>(8));for(auto&r:c)for(int&x:r)cin>>x;const int INF=1e9;vector<int>dp(1<<n,INF);dp[0]=0;for(int mask=0;mask<(1<<n);mask++){int row=__builtin_popcount((unsigned)mask);if(row>=n)continue;for(int col=0;col<n;col++)if(!(mask>>col&1))dp[mask|1<<col]=min(dp[mask|1<<col],dp[mask]+c[row][col]);}cout<<dp[(1<<n)-1]<<'\\n';}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("three_workers", 1, "3\n9 2 7 0 0 0 0 0\n6 4 3 0 0 0 0 0\n5 8 1 0 0 0 0 0\n"),
                new GeneratedTestCase("four_workers", 2, "4\n10 2 9 7 0 0 0 0\n6 4 3 7 0 0 0 0\n5 8 1 8 0 0 0 0\n7 6 9 4 0 0 0 0\n")
        );
        saveGeneratedCases(saved, KnownContracts.bitmaskAssignment(), cases, this::solveBitmaskAssignment, 1);
        return saved;
    }

    private Problem generateFibonacciPowerProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Fibonacci Power");
        setProblemDescription(problem, source, "Given n and k, compute the sum of the first n Fibonacci numbers raised to power k modulo 998244353.");
        problem.setInputFormat("The input contains two integers n and k.");
        problem.setOutputFormat("Print the requested sum modulo 998244353.");
        problem.setConstraints("Generated seed data uses 1 <= n <= 20 and 1 <= k <= 8 for exact backend verification.");
        problem.setValidatorCode("""
                import sys
                data=list(map(int,sys.stdin.read().strip().split()))
                if len(data)!=2: raise SystemExit('expected n and k')
                n,k=data
                if not(1<=n<=20 and 1<=k<=8): raise SystemExit('out of seed range')
                """);
        problem.setTestPlan("""
                Contract-first v2 seed pattern:
                - Classified family: NUMBER_THEORY.
                - Inferred pattern: FIBONACCI_POWER_SUM.
                - Backend seed oracle computes exact small Fibonacci values and modular powers directly.
                - This seed is intentionally small-range until a trusted large-n recurrence/oracle is added.
                """);
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std;
                static const long long MOD=998244353;
                long long modpow(long long a,long long e){long long r=1%MOD;while(e){if(e&1)r=r*a%MOD;a=a*a%MOD;e>>=1;}return r;}
                int main(){long long n,k;if(!(cin>>n>>k))return 0;vector<long long>f(max(3LL,n+1));f[0]=0;f[1]=1;for(int i=2;i<=n;i++)f[i]=(f[i-1]+f[i-2])%MOD;long long ans=0;for(int i=1;i<=n;i++)ans=(ans+modpow(f[i],k))%MOD;cout<<ans<<'\\n';}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("tiny", 1, "1 1\n"),
                new GeneratedTestCase("small_square", 2, "5 2\n"),
                new GeneratedTestCase("higher_power", 3, "10 5\n"),
                new GeneratedTestCase("seed_upper", 4, "20 8\n")
        );
        saveGeneratedCases(saved, KnownContracts.fibonacciPowerSum(), cases, this::solveFibonacciPower, 2);
        return saved;
    }

    private Problem generatePolygonSubsetProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Intricate Polygons");
        setProblemDescription(problem, source, "Count subsets of stick lengths that can form a polygon.");
        problem.setInputFormat("The first line contains n. The next line contains n stick lengths.");
        problem.setOutputFormat("Print the number of valid polygon subsets modulo 1000000007.");
        problem.setConstraints("Generated seed data uses 1 <= n <= 14 and 1 <= a_i <= 30.");
        problem.setTestPlan("Seed pattern: POLYGON_SUBSET_COUNT.");
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("all_equal", 1, "4\n2 2 2 2\n"),
                new GeneratedTestCase("mixed_lengths", 2, "5\n1 2 3 4 5\n"),
                new GeneratedTestCase("dominant_stick", 3, "6\n1 2 3 4 5 20\n"),
                new GeneratedTestCase("duplicates", 4, "7\n3 3 3 6 7 8 9\n")
        );
        saveGeneratedCases(saved, KnownContracts.polygonSubsetCount(), cases, this::solvePolygonSubsetCount, 2);
        return saved;
    }

    private Problem generateWeightedSequenceSumProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Journey to Sequence Sum");
        setProblemDescription(problem, source, "For each test case, compute sum_{i=1..n} i^K * R^i modulo 1000000007.");
        problem.setInputFormat("The first line contains T. Each test case contains K, n, and R.");
        problem.setOutputFormat("Print one modular sum per test case.");
        problem.setConstraints("Generated seed data uses 1 <= T <= 4, 1 <= K <= 8, 1 <= n <= 20, 2 <= R <= 20.");
        problem.setTestPlan("Seed pattern: WEIGHTED_SEQUENCE_SUM.");
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("sample_like", 1, "2\n1 1 2\n3 4 2\n"),
                new GeneratedTestCase("mixed_small", 2, "3\n1 5 2\n2 6 3\n4 8 5\n"),
                new GeneratedTestCase("larger_seed", 3, "2\n6 12 7\n8 20 11\n")
        );
        saveGeneratedCases(saved, KnownContracts.weightedSequenceSum(), cases, this::solveWeightedSequenceSum, 1);
        return saved;
    }

    private Problem generateInsertionInversionProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Mingle Lineup");
        setProblemDescription(problem, source, "Insert sequence B into A while preserving A's order and minimizing total inversions.");
        problem.setInputFormat("The first line contains T. Each test case contains n, m, then arrays A and B.");
        problem.setOutputFormat("Print the minimum possible inversion count for each test case.");
        problem.setConstraints("Generated seed data uses 1 <= T <= 4, 1 <= n,m <= 12.");
        problem.setTestPlan("Seed pattern: INSERTION_INVERSION_MINIMIZATION.");
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("sample_like", 1, "3\n3 3\n3 2 1\n1 2 3\n3 4\n1 2 3\n4 3 2 1\n5 4\n1 3 5 3 1\n4 3 6 1\n"),
                new GeneratedTestCase("already_sorted", 2, "2\n4 3\n1 2 3 4\n1 2 3\n4 4\n4 3 2 1\n1 2 3 4\n")
        );
        saveGeneratedCases(saved, KnownContracts.insertionInversionMinimization(), cases, this::solveInsertionInversion, 1);
        return saved;
    }

    private Problem generateFenwickProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Fenwick Range Sum");
        setProblemDescription(problem, source, "Maintain an array under ADD i delta and SUM l r operations.");
        problem.setInputFormat("The first line contains n and q. The second line contains n integers. The next q lines are ADD or SUM commands.");
        problem.setOutputFormat("For each SUM command, print the range sum.");
        problem.setConstraints("Generated judge data uses 1 <= n <= 50 and 1 <= q <= 80.");
        problem.setValidatorCode(commandArrayValidatorCode(false));
        problem.setTestPlan("""
                Contract-first v2 seed pattern:
                - Classified family: DATA_STRUCTURE.
                - Inferred pattern: FENWICK_POINT_UPDATE_RANGE_SUM.
                - Java oracle simulates point updates directly.
                """);
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std; struct BIT{int n;vector<long long>b;BIT(int n):n(n),b(n+1){}void add(int i,long long v){for(;i<=n;i+=i&-i)b[i]+=v;}long long sum(int i){long long s=0;for(;i;i-=i&-i)s+=b[i];return s;}};
                int main(){int n,q;if(!(cin>>n>>q))return 0;BIT bit(n);for(int i=1,x;i<=n;i++){cin>>x;bit.add(i,x);}while(q--){string op;cin>>op;if(op=="ADD"){int i,d;cin>>i>>d;bit.add(i,d);}else{int l,r;cin>>l>>r;cout<<bit.sum(r)-bit.sum(l-1)<<'\\n';}}}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("mixed", 1, "5 5\n1 2 3 4 5\nSUM 1 5\nADD 3 10\nSUM 2 4\nADD 1 -1\nSUM 1 1\n"),
                new GeneratedTestCase("single", 2, "1 3\n7\nSUM 1 1\nADD 1 5\nSUM 1 1\n")
        );
        saveGeneratedCases(saved, KnownContracts.fenwickPointUpdateRangeSum(), cases, this::solveFenwick, 1);
        return saved;
    }

    private Problem generateSegmentTreeProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Segment Tree Range Sum");
        setProblemDescription(problem, source, "Maintain an array under range ADD l r delta and SUM l r operations.");
        problem.setInputFormat("The first line contains n and q. The second line contains n integers. The next q lines are ADD or SUM commands.");
        problem.setOutputFormat("For each SUM command, print the range sum.");
        problem.setConstraints("Generated judge data uses 1 <= n <= 50 and 1 <= q <= 80.");
        problem.setValidatorCode(commandArrayValidatorCode(true));
        problem.setTestPlan("""
                Contract-first v2 seed pattern:
                - Classified family: DATA_STRUCTURE.
                - Inferred pattern: SEGMENT_TREE_RANGE_SUM_UPDATE.
                - Java oracle simulates range updates directly.
                """);
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std; int main(){int n,q;if(!(cin>>n>>q))return 0;vector<long long>a(n+1);for(int i=1;i<=n;i++)cin>>a[i];while(q--){string op;cin>>op;if(op=="ADD"){int l,r,d;cin>>l>>r>>d;for(int i=l;i<=r;i++)a[i]+=d;}else{int l,r;cin>>l>>r;long long s=0;for(int i=l;i<=r;i++)s+=a[i];cout<<s<<'\\n';}}}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("mixed", 1, "5 5\n1 2 3 4 5\nSUM 1 5\nADD 2 4 3\nSUM 1 5\nADD 1 5 -1\nSUM 3 3\n"),
                new GeneratedTestCase("nested", 2, "4 4\n0 0 0 0\nADD 1 4 5\nADD 2 3 -2\nSUM 1 4\nSUM 2 3\n")
        );
        saveGeneratedCases(saved, KnownContracts.segmentTreeRangeSumUpdate(), cases, this::solveSegmentTree, 1);
        return saved;
    }

    private Problem generateBinarySearchProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Lower Bound");
        setProblemDescription(problem, source, "Given a sorted array and x, find the first 1-based position with value >= x.");
        problem.setInputFormat("The first line contains n and x. The second line contains n sorted integers.");
        problem.setOutputFormat("Print the lower-bound position, or n+1 if absent.");
        problem.setConstraints("Generated judge data uses 1 <= n <= 100.");
        problem.setValidatorCode(simpleArrayValidatorCode());
        problem.setTestPlan("Seed pattern: BINARY_SEARCH_LOWER_BOUND.");
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std; int main(){int n;long long x;if(!(cin>>n>>x))return 0;vector<long long>a(n);for(auto&v:a)cin>>v;cout<<(lower_bound(a.begin(),a.end(),x)-a.begin()+1)<<'\\n';}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("inside", 1, "5 4\n1 3 4 4 9\n"),
                new GeneratedTestCase("after_all", 2, "4 10\n1 2 3 4\n")
        );
        saveGeneratedCases(saved, KnownContracts.binarySearchLowerBound(), cases, this::solveLowerBound, 1);
        return saved;
    }

    private Problem generateMinFeasibleProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Minimum Feasible Integer");
        setProblemDescription(problem, source, "Find the smallest positive n such that A divides n, B does not divide n, n divides C, and n does not divide D.");
        problem.setInputFormat("The input contains four integers A, B, C, and D.");
        problem.setOutputFormat("Print the smallest feasible n, or -1 if none exists.");
        problem.setConstraints("Generated seed data uses 1 <= A, B, C, D <= 100.");
        problem.setValidatorCode("""
                import sys
                data=list(map(int,sys.stdin.read().strip().split()))
                if len(data)!=4: raise SystemExit('expected A B C D')
                if any(x<1 or x>100 for x in data): raise SystemExit('out of range')
                """);
        problem.setTestPlan("""
                Contract-first v2 seed pattern:
                - Classified family: GENERAL.
                - Inferred pattern: BINARY_SEARCH_MIN_FEASIBLE.
                - Java oracle enumerates small n exactly; the pattern represents 'smallest feasible answer' tasks.
                """);
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std;
                int main(){long long A,B,C,D;if(!(cin>>A>>B>>C>>D))return 0;for(long long n=A;n<=C;n+=A)if(n%B!=0&&C%n==0&&D%n!=0){cout<<n<<'\\n';return 0;}cout<<-1<<'\\n';}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("possible", 1, "2 3 12 10\n"),
                new GeneratedTestCase("impossible", 2, "4 2 16 8\n"),
                new GeneratedTestCase("larger_answer", 3, "3 2 36 18\n")
        );
        saveGeneratedCases(saved, KnownContracts.binarySearchMinFeasible(), cases, this::solveMinFeasible, 1);
        return saved;
    }

    private Problem generateTwoPointersProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Pair Count");
        setProblemDescription(problem, source, "Given an array and target, count pairs i < j whose sum is at most target.");
        problem.setInputFormat("The first line contains n and target. The second line contains n integers.");
        problem.setOutputFormat("Print the number of valid pairs.");
        problem.setConstraints("Generated judge data uses 1 <= n <= 100.");
        problem.setValidatorCode(simpleArrayValidatorCode());
        problem.setTestPlan("Seed pattern: TWO_POINTER_PAIR_COUNT.");
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std; int main(){int n;long long t;if(!(cin>>n>>t))return 0;vector<long long>a(n);for(auto&x:a)cin>>x;sort(a.begin(),a.end());long long ans=0;for(int l=0,r=n-1;l<r;){if(a[l]+a[r]<=t){ans+=r-l;l++;}else r--;}cout<<ans<<'\\n';}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("mixed", 1, "5 7\n1 2 3 4 5\n"),
                new GeneratedTestCase("negative", 2, "4 0\n-5 -1 2 8\n")
        );
        saveGeneratedCases(saved, KnownContracts.twoPointerPairCount(), cases, this::solveTwoPointers, 1);
        return saved;
    }

    private Problem generateGreedyIntervalsProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Interval Scheduling");
        setProblemDescription(problem, source, "Choose the maximum number of pairwise non-overlapping intervals.");
        problem.setInputFormat("The first line contains n. The next n lines contain l and r.");
        problem.setOutputFormat("Print the maximum number of intervals.");
        problem.setConstraints("Generated judge data uses 1 <= n <= 100.");
        problem.setValidatorCode("""
                import sys
                data=list(map(int,sys.stdin.read().strip().split()))
                if not data: raise SystemExit('missing n')
                n=data[0]
                if not(1<=n<=100) or len(data)!=1+2*n: raise SystemExit('bad shape')
                """);
        problem.setTestPlan("Seed pattern: GREEDY_INTERVAL_SCHEDULING.");
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std; int main(){int n;if(!(cin>>n))return 0;vector<pair<int,int>>v(n);for(auto&[l,r]:v)cin>>l>>r;sort(v.begin(),v.end(),[](auto a,auto b){return a.second<b.second;});int ans=0,last=-1e9;for(auto [l,r]:v)if(l>=last){ans++;last=r;}cout<<ans<<'\\n';}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("classic", 1, "5\n1 3\n2 5\n4 7\n6 9\n8 10\n"),
                new GeneratedTestCase("nested", 2, "4\n1 10\n2 3\n3 4\n4 5\n")
        );
        saveGeneratedCases(saved, KnownContracts.greedyIntervalScheduling(), cases, this::solveGreedyIntervals, 1);
        return saved;
    }

    private Problem generateStringHashProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Substring Equality");
        setProblemDescription(problem, source, "Given a lowercase string encoded as letters 1..26 and substring queries, decide equality of each pair.");
        problem.setInputFormat("The first line contains n and q. The second line contains n lowercase-letter codes. Each query contains l1 r1 l2 r2.");
        problem.setOutputFormat("Print YES or NO per query.");
        problem.setConstraints("Generated seed data uses 1 <= n <= 20.");
        problem.setValidatorCode("""
                import sys
                data=list(map(int,sys.stdin.read().strip().split()))
                if len(data)<2: raise SystemExit('missing header')
                n,q=data[:2]
                if not(1<=n<=20 and 1<=q<=30): raise SystemExit('bad header')
                if len(data)!=2+n+4*q: raise SystemExit('bad shape')
                """);
        problem.setTestPlan("Seed pattern: STRING_SUBSTRING_EQUALITY.");
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std; int main(){int n,q;if(!(cin>>n>>q))return 0;vector<int>a(n+1);for(int i=1;i<=n;i++)cin>>a[i];while(q--){int l1,r1,l2,r2;cin>>l1>>r1>>l2>>r2;bool ok=r1-l1==r2-l2;for(int i=0;ok&&i<=r1-l1;i++)ok&=a[l1+i]==a[l2+i];cout<<(ok?\"YES\":\"NO\")<<'\\n';}}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("queries", 1, "6 3\n1 2 1 2 3 1\n1 2 3 4\n1 3 2 4\n1 1 6 6\n")
        );
        saveGeneratedCases(saved, KnownContracts.stringHashSubstringEquality(), cases, this::solveStringHash, 1);
        return saved;
    }

    private Problem generateConstructiveProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Even Odd Permutation");
        setProblemDescription(problem, source, "Construct a permutation of 1..n by printing all even numbers first, then all odd numbers.");
        problem.setInputFormat("The input contains n.");
        problem.setOutputFormat("Print the required permutation.");
        problem.setConstraints("Generated judge data uses 1 <= n <= 30.");
        problem.setValidatorCode("import sys\nn=int(sys.stdin.read().strip()); assert 1<=n<=30\n");
        problem.setTestPlan("Seed pattern: CONSTRUCTIVE_EVEN_ODD_PERMUTATION.");
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std; int main(){int n;if(!(cin>>n))return 0;bool first=true;for(int p:{0,1})for(int i=1;i<=n;i++)if(i%2==p){if(!first)cout<<' ';first=false;cout<<i;}cout<<'\\n';}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("small", 1, "5\n"),
                new GeneratedTestCase("even", 2, "6\n")
        );
        saveGeneratedCases(saved, KnownContracts.constructiveEvenOddPermutation(), cases, this::solveConstructive, 1);
        return saved;
    }

    private Problem generateMaxWelterGameProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "A Game with Cows");
        setProblemDescription(problem, source, """
                Hieu and RR play with cows placed in distinct stalls. On each turn, the player must move the rightmost cow
                to any empty stall on its left. A player who cannot move loses. Determine the winner under optimal play.
                """);
        problem.setInputFormat("""
                The first line contains T. Each test case contains N followed by one line of N pairwise-distinct stall positions.
                """);
        problem.setOutputFormat("For each test case, print Hieu if the first player wins, otherwise RR.");
        problem.setConstraints("""
                Generated judge data uses 1 <= T <= 6, 1 <= N <= 20, and 1 <= a_i <= 1000000000.
                Stall positions are pairwise distinct inside each test case.
                """);
        problem.setValidatorCode("""
                import sys
                data = list(map(int, sys.stdin.read().strip().split()))
                if not data: raise SystemExit('missing T')
                t = data[0]
                if not (1 <= t <= 6): raise SystemExit('T out of range')
                i = 1
                for _ in range(t):
                    if i >= len(data): raise SystemExit('missing N')
                    n = data[i]; i += 1
                    if not (1 <= n <= 20): raise SystemExit('N out of range')
                    if i + n > len(data): raise SystemExit('missing positions')
                    arr = data[i:i+n]; i += n
                    if any(x < 1 or x > 1000000000 for x in arr): raise SystemExit('a_i out of range')
                    if len(set(arr)) != n: raise SystemExit('positions must be distinct')
                if i != len(data): raise SystemExit('extra tokens')
                """);
        problem.setTestPlan("""
                Contract-first v2 plan:
                - Classified family: GAME_THEORY.
                - Backend emits multi-test N + array inputs with an explicit leading T.
                - Java oracle uses the local Max-Welter characterization after sorting stall positions.
                - Profiles cover terminal positions, consecutive-rightmost losing states, parity traps, and sparse large stalls.
                """);
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std;
                int main(){ios::sync_with_stdio(false);cin.tie(nullptr);int T;if(!(cin>>T))return 0;while(T--){int n;cin>>n;vector<long long>a(n);for(auto &x:a)cin>>x;sort(a.begin(),a.end());bool rr=false;if(n==1) rr=(a[0]==1); else rr=(a[n-1]==a[n-2]+1 && ((a[n-2]+n)%2==1));cout<<(rr?\"RR\":\"Hieu\")<<'\\n';}}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("sample_like_mix", 1, "5\n1\n1\n2\n1 2\n2\n1 3\n3\n1 2 3\n4\n2 5 9 10\n"),
                new GeneratedTestCase("parity_traps", 2, "4\n2\n4 5\n3\n2 4 5\n4\n1 4 8 9\n5\n3 10 20 21 30\n"),
                new GeneratedTestCase("large_sparse", 3, "3\n2\n999999999 1000000000\n3\n1 500000000 1000000000\n6\n7 100 1000 10000 100000 1000000000\n")
        );
        saveGeneratedCases(saved, KnownContracts.maxWelterGame(), cases, this::solveMaxWelterGame, 1);
        return saved;
    }

    private Problem generateSubtractionGameProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Subtraction Game");
        setProblemDescription(problem, source, "For each pile size n, two players alternate removing 1, 2, or 3 stones. The player who takes the last stone wins.");
        problem.setInputFormat("The first line contains T. Each test case contains one integer n.");
        problem.setOutputFormat("Print First if the starting player wins, otherwise Second.");
        problem.setConstraints("Generated judge data uses 1 <= T <= 6 and 1 <= n <= 1000000000.");
        problem.setValidatorCode("""
                import sys
                data = list(map(int, sys.stdin.read().strip().split()))
                if not data: raise SystemExit('missing T')
                t = data[0]
                if not (1 <= t <= 6) or len(data) != t + 1: raise SystemExit('invalid shape')
                if any(x < 1 or x > 1000000000 for x in data[1:]): raise SystemExit('n out of range')
                """);
        problem.setTestPlan("""
                Contract-first v2 plan:
                - Classified family: GAME_THEORY.
                - Inferred pattern: SUBTRACTION_GAME from the move set {1,2,3}.
                - Backend emits explicit T and uses the n mod 4 losing-position oracle.
                """);
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std;
                int main(){ios::sync_with_stdio(false);cin.tie(nullptr);int T;if(!(cin>>T))return 0;while(T--){long long n;cin>>n;cout<<(n%4?\"First\":\"Second\")<<'\\n';}}
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("small_cycle", 1, "6\n1\n2\n3\n4\n5\n8\n"),
                new GeneratedTestCase("large_values", 2, "4\n999999997\n999999998\n999999999\n1000000000\n")
        );
        saveGeneratedCases(saved, KnownContracts.subtractionGame(), cases, this::solveSubtractionGame, 1);
        return saved;
    }

    private Problem baseProblem(String requestedTitle, String fallbackTitle) {
        Problem problem = new Problem();
        problem.setTitle(requestedTitle == null || requestedTitle.isBlank() ? fallbackTitle : requestedTitle.trim());
        problem.setTimeLimit(1000);
        problem.setMemoryLimit(256);
        problem.setCreatedAt(LocalDateTime.now());
        problem.setAcceptedSolutionLanguage("cpp");
        return problem;
    }

    private void setProblemDescription(Problem problem, String source, String fallback) {
        if (source != null && !source.isBlank()) {
            problem.setDescription(source.trim());
        } else {
            problem.setDescription(fallback);
        }
    }

    private void saveGeneratedCases(Problem saved, List<GeneratedTestCase> cases,
                                    java.util.function.Function<String, String> solver, int samples) {
        saveGeneratedCases(saved, null, cases, solver, samples);
    }

    private void saveGeneratedCases(Problem saved, com.pbj.v2.contract.ProblemContract contract, List<GeneratedTestCase> cases,
                                    java.util.function.Function<String, String> solver, int samples) {
        ContractValidator validator = contract == null ? null : new ContractValidator();
        List<GeneratedTestCase> targetCases = cases;
        if (contract != null) {
            ContractTestcaseGenerator contractGenerator = new ContractTestcaseGenerator(validator);
            List<String> profiles = List.of(
                    "edge_boundary",
                    "random_small",
                    "random_medium",
                    "random_large",
                    "extreme_edge",
                    "random_stress"
            );
            List<GeneratedTestCase> dynamicCases = new ArrayList<>();
            int seed = 1;
            for (String profile : profiles) {
                try {
                    GeneratedTestCase generated = contractGenerator.generate(contract, profile, seed++);
                    dynamicCases.add(generated);
                } catch (Exception e) {
                    System.err.println("WARN: Dynamic testcase generation failed for profile " + profile + ": " + e.getMessage());
                }
            }
            if (!dynamicCases.isEmpty()) {
                targetCases = dynamicCases;
            }
        }
        int seq = 1;
        for (GeneratedTestCase testCase : targetCases) {
            if (validator != null) {
                try {
                    validator.validateInput(contract, testCase.input());
                } catch (Exception e) {
                    System.err.println("WARN: Dynamic input validation failed for case: " + e.getMessage());
                }
            }
            testCaseStorageService.saveTestCase(saved, testCase.input(), solver.apply(testCase.input()), seq <= samples, seq++);
        }
    }

    private long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    private String solveGcdPair(String input) {
        FastScanner fs = new FastScanner(input);
        return gcd(fs.nextLong(), fs.nextLong()) + "\n";
    }

    private String solveMaximumSubarray(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt();
        long best = Long.MIN_VALUE;
        long cur = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            long x = fs.nextLong();
            cur = i == 0 ? x : Math.max(x, cur + x);
            best = Math.max(best, cur);
        }
        return best + "\n";
    }

    private String solveArraySum(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt();
        long sum = 0;
        for (int i = 0; i < n; i++) sum += fs.nextLong();
        return sum + "\n";
    }

    private String solveTreeDistances(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt();
        int q = fs.nextInt();
        int e = fs.nextInt();
        List<List<Integer>> graph = new ArrayList<>();
        for (int i = 0; i <= n; i++) graph.add(new ArrayList<>());
        for (int i = 0; i < e; i++) {
            int u = fs.nextInt();
            int v = fs.nextInt();
            graph.get(u).add(v);
            graph.get(v).add(u);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < q; i++) {
            int s = fs.nextInt();
            int t = fs.nextInt();
            int[] dist = new int[n + 1];
            java.util.Arrays.fill(dist, -1);
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            queue.add(s);
            dist[s] = 0;
            while (!queue.isEmpty()) {
                int u = queue.remove();
                for (int v : graph.get(u)) {
                    if (dist[v] < 0) {
                        dist[v] = dist[u] + 1;
                        queue.add(v);
                    }
                }
            }
            out.append(dist[t]).append('\n');
        }
        return out.toString();
    }

    private String solveGraphShortestPath(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt(), m = fs.nextInt(), s = fs.nextInt(), t = fs.nextInt();
        List<List<long[]>> graph = new ArrayList<>();
        for (int i = 0; i <= n; i++) graph.add(new ArrayList<>());
        for (int i = 0; i < m; i++) {
            int u = fs.nextInt(), v = fs.nextInt(), w = fs.nextInt();
            graph.get(u).add(new long[] {v, w});
            graph.get(v).add(new long[] {u, w});
        }
        long[] dist = new long[n + 1];
        java.util.Arrays.fill(dist, Long.MAX_VALUE / 4);
        java.util.PriorityQueue<long[]> pq = new java.util.PriorityQueue<>(java.util.Comparator.comparingLong(a -> a[0]));
        dist[s] = 0;
        pq.add(new long[] {0, s});
        while (!pq.isEmpty()) {
            long[] cur = pq.remove();
            if (cur[0] != dist[(int) cur[1]]) continue;
            for (long[] edge : graph.get((int) cur[1])) {
                int v = (int) edge[0];
                long nd = cur[0] + edge[1];
                if (nd < dist[v]) {
                    dist[v] = nd;
                    pq.add(new long[] {nd, v});
                }
            }
        }
        return dist[t] + "\n";
    }

    private String solveGraphReachability(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt(), m = fs.nextInt(), s = fs.nextInt();
        List<List<Integer>> graph = new ArrayList<>();
        for (int i = 0; i <= n; i++) graph.add(new ArrayList<>());
        for (int i = 0; i < m; i++) {
            int u = fs.nextInt(), v = fs.nextInt();
            graph.get(u).add(v);
            graph.get(v).add(u);
        }
        boolean[] seen = new boolean[n + 1];
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        queue.add(s);
        seen[s] = true;
        int count = 0;
        while (!queue.isEmpty()) {
            int u = queue.remove();
            count++;
            for (int v : graph.get(u)) if (!seen[v]) {
                seen[v] = true;
                queue.add(v);
            }
        }
        return count + "\n";
    }

    private String solveGraphDsuComponents(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt(), m = fs.nextInt();
        int[] parent = new int[n + 1];
        for (int i = 1; i <= n; i++) parent[i] = i;
        for (int i = 0; i < m; i++) {
            int u = fs.nextInt(), v = fs.nextInt();
            union(parent, u, v);
        }
        java.util.Set<Integer> roots = new java.util.HashSet<>();
        for (int i = 1; i <= n; i++) roots.add(find(parent, i));
        return roots.size() + "\n";
    }

    private String solveGraphToposort(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt(), m = fs.nextInt();
        List<List<Integer>> graph = new ArrayList<>();
        for (int i = 0; i <= n; i++) graph.add(new ArrayList<>());
        int[] indeg = new int[n + 1];
        for (int i = 0; i < m; i++) {
            int u = fs.nextInt(), v = fs.nextInt();
            graph.get(u).add(v);
            indeg[v]++;
        }
        java.util.PriorityQueue<Integer> pq = new java.util.PriorityQueue<>();
        for (int i = 1; i <= n; i++) if (indeg[i] == 0) pq.add(i);
        StringBuilder out = new StringBuilder();
        while (!pq.isEmpty()) {
            int u = pq.remove();
            if (!out.isEmpty()) out.append(' ');
            out.append(u);
            for (int v : graph.get(u)) if (--indeg[v] == 0) pq.add(v);
        }
        return out.append('\n').toString();
    }

    private String solveTreeDp(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt(), e = fs.nextInt();
        long[] value = new long[n + 1];
        for (int i = 1; i <= n; i++) value[i] = fs.nextLong();
        List<List<Integer>> graph = new ArrayList<>();
        for (int i = 0; i <= n; i++) graph.add(new ArrayList<>());
        for (int i = 0; i < e; i++) {
            int u = fs.nextInt(), v = fs.nextInt();
            graph.get(u).add(v); graph.get(v).add(u);
        }
        long[] sub = new long[n + 1];
        dfsTree(1, 0, graph, value, sub);
        StringBuilder out = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            if (i > 1) out.append(' ');
            out.append(sub[i]);
        }
        return out.append('\n').toString();
    }

    private void dfsTree(int u, int p, List<List<Integer>> graph, long[] value, long[] sub) {
        sub[u] = value[u];
        for (int v : graph.get(u)) if (v != p) {
            dfsTree(v, u, graph, value, sub);
            sub[u] += sub[v];
        }
    }

    private int find(int[] parent, int x) {
        if (parent[x] == x) return x;
        parent[x] = find(parent, parent[x]);
        return parent[x];
    }

    private void union(int[] parent, int a, int b) {
        a = find(parent, a);
        b = find(parent, b);
        if (a != b) parent[a] = b;
    }

    private String solveRangeSums(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt(), q = fs.nextInt();
        long[] pref = new long[n + 1];
        for (int i = 1; i <= n; i++) pref[i] = pref[i - 1] + fs.nextLong();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < q; i++) {
            int l = fs.nextInt(), r = fs.nextInt();
            out.append(pref[r] - pref[l - 1]).append('\n');
        }
        return out.toString();
    }

    private String solveCoinChange(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt(), target = fs.nextInt();
        int[] coins = new int[n];
        for (int i = 0; i < n; i++) coins[i] = fs.nextInt();
        int inf = 1_000_000_000;
        int[] dp = new int[target + 1];
        java.util.Arrays.fill(dp, inf);
        dp[0] = 0;
        for (int x = 1; x <= target; x++) {
            for (int coin : coins) {
                if (x >= coin) dp[x] = Math.min(dp[x], dp[x - coin] + 1);
            }
        }
        return (dp[target] >= inf ? -1 : dp[target]) + "\n";
    }

    private String solveKnapsack(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt(), capacity = fs.nextInt();
        int[] dp = new int[capacity + 1];
        for (int i = 0; i < n; i++) {
            int w = fs.nextInt(), v = fs.nextInt();
            for (int cap = capacity; cap >= w; cap--) dp[cap] = Math.max(dp[cap], dp[cap - w] + v);
        }
        return dp[capacity] + "\n";
    }

    private String solveLisLength(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt();
        long[] a = new long[n];
        for (int i = 0; i < n; i++) a[i] = fs.nextLong();
        int[] dp = new int[n];
        int ans = 0;
        for (int i = 0; i < n; i++) {
            dp[i] = 1;
            for (int j = 0; j < i; j++) if (a[j] < a[i]) dp[i] = Math.max(dp[i], dp[j] + 1);
            ans = Math.max(ans, dp[i]);
        }
        return ans + "\n";
    }

    private String solveBitmaskAssignment(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt();
        int[][] cost = new int[n][8];
        for (int i = 0; i < n; i++) for (int j = 0; j < 8; j++) cost[i][j] = fs.nextInt();
        int inf = 1_000_000_000;
        int[] dp = new int[1 << n];
        java.util.Arrays.fill(dp, inf);
        dp[0] = 0;
        for (int mask = 0; mask < (1 << n); mask++) {
            int row = Integer.bitCount(mask);
            if (row >= n) continue;
            for (int col = 0; col < n; col++) if ((mask & (1 << col)) == 0) {
                int next = mask | (1 << col);
                dp[next] = Math.min(dp[next], dp[mask] + cost[row][col]);
            }
        }
        return dp[(1 << n) - 1] + "\n";
    }

    private String solveFibonacciPower(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt();
        int k = fs.nextInt();
        long mod = 998_244_353L;
        long[] fib = new long[Math.max(3, n + 1)];
        fib[1] = 1;
        for (int i = 2; i <= n; i++) fib[i] = (fib[i - 1] + fib[i - 2]) % mod;
        long sum = 0;
        for (int i = 1; i <= n; i++) {
            sum = (sum + modPow(fib[i], k, mod)) % mod;
        }
        return sum + "\n";
    }

    private String solvePolygonSubsetCount(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt();
        long[] a = new long[n];
        for (int i = 0; i < n; i++) a[i] = fs.nextLong();
        long ans = 0;
        for (int mask = 0; mask < (1 << n); mask++) {
            if (Integer.bitCount(mask) < 3) continue;
            long sum = 0, mx = 0;
            for (int i = 0; i < n; i++) if ((mask & (1 << i)) != 0) {
                sum += a[i];
                mx = Math.max(mx, a[i]);
            }
            if (sum - mx > mx) ans++;
        }
        return (ans % 1_000_000_007L) + "\n";
    }

    private String solveWeightedSequenceSum(String input) {
        FastScanner fs = new FastScanner(input);
        int t = fs.nextInt();
        long mod = 1_000_000_007L;
        StringBuilder out = new StringBuilder();
        while (t-- > 0) {
            int k = fs.nextInt();
            int n = fs.nextInt();
            long r = fs.nextLong();
            long sum = 0;
            for (int i = 1; i <= n; i++) {
                sum = (sum + modPow(i, k, mod) * modPow(r, i, mod)) % mod;
            }
            out.append(sum).append('\n');
        }
        return out.toString();
    }

    private String solveFenwick(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt(), q = fs.nextInt();
        long[] a = new long[n + 1];
        for (int i = 1; i <= n; i++) a[i] = fs.nextLong();
        StringBuilder out = new StringBuilder();
        while (q-- > 0) {
            String op = fs.next();
            if ("ADD".equals(op)) a[fs.nextInt()] += fs.nextLong();
            else {
                int l = fs.nextInt(), r = fs.nextInt();
                long sum = 0;
                for (int i = l; i <= r; i++) sum += a[i];
                out.append(sum).append('\n');
            }
        }
        return out.toString();
    }

    private String solveSegmentTree(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt(), q = fs.nextInt();
        long[] a = new long[n + 1];
        for (int i = 1; i <= n; i++) a[i] = fs.nextLong();
        StringBuilder out = new StringBuilder();
        while (q-- > 0) {
            String op = fs.next();
            int l = fs.nextInt(), r = fs.nextInt();
            if ("ADD".equals(op)) {
                long d = fs.nextLong();
                for (int i = l; i <= r; i++) a[i] += d;
            } else {
                long sum = 0;
                for (int i = l; i <= r; i++) sum += a[i];
                out.append(sum).append('\n');
            }
        }
        return out.toString();
    }

    private String solveLowerBound(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt();
        long x = fs.nextLong();
        int ans = n + 1;
        for (int i = 1; i <= n; i++) {
            long v = fs.nextLong();
            if (ans == n + 1 && v >= x) ans = i;
        }
        return ans + "\n";
    }

    private String solveMinFeasible(String input) {
        FastScanner fs = new FastScanner(input);
        long a = fs.nextLong(), b = fs.nextLong(), c = fs.nextLong(), d = fs.nextLong();
        for (long n = a; n <= c; n += a) {
            if (n % b != 0 && c % n == 0 && d % n != 0) return n + "\n";
        }
        return "-1\n";
    }

    private String solveTwoPointers(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt();
        long target = fs.nextLong();
        long[] a = new long[n];
        for (int i = 0; i < n; i++) a[i] = fs.nextLong();
        java.util.Arrays.sort(a);
        long ans = 0;
        for (int l = 0, r = n - 1; l < r;) {
            if (a[l] + a[r] <= target) {
                ans += r - l;
                l++;
            } else r--;
        }
        return ans + "\n";
    }

    private String solveGreedyIntervals(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt();
        int[][] v = new int[n][2];
        for (int i = 0; i < n; i++) {
            v[i][0] = fs.nextInt();
            v[i][1] = fs.nextInt();
        }
        java.util.Arrays.sort(v, java.util.Comparator.comparingInt(a -> a[1]));
        int ans = 0, last = Integer.MIN_VALUE;
        for (int[] it : v) if (it[0] >= last) {
            ans++;
            last = it[1];
        }
        return ans + "\n";
    }

    private String solveInsertionInversion(String input) {
        FastScanner fs = new FastScanner(input);
        int t = fs.nextInt();
        StringBuilder out = new StringBuilder();
        while (t-- > 0) {
            int n = fs.nextInt(), m = fs.nextInt();
            long[] a = new long[n];
            long[] b = new long[m];
            for (int i = 0; i < n; i++) a[i] = fs.nextLong();
            for (int i = 0; i < m; i++) b[i] = fs.nextLong();
            long ans = 0;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) if (a[i] > a[j]) ans++;
            }
            java.util.Arrays.sort(b);
            for (long x : b) {
                long best = Long.MAX_VALUE;
                for (int gap = 0; gap <= n; gap++) {
                    long cost = 0;
                    for (int i = 0; i < gap; i++) if (a[i] > x) cost++;
                    for (int i = gap; i < n; i++) if (a[i] < x) cost++;
                    best = Math.min(best, cost);
                }
                ans += best;
            }
            out.append(ans).append('\n');
        }
        return out.toString();
    }

    private String solveStringHash(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt(), q = fs.nextInt();
        int[] a = new int[n + 1];
        for (int i = 1; i <= n; i++) a[i] = fs.nextInt();
        StringBuilder out = new StringBuilder();
        while (q-- > 0) {
            int l1 = fs.nextInt(), r1 = fs.nextInt(), l2 = fs.nextInt(), r2 = fs.nextInt();
            boolean ok = r1 - l1 == r2 - l2;
            for (int i = 0; ok && i <= r1 - l1; i++) ok = a[l1 + i] == a[l2 + i];
            out.append(ok ? "YES" : "NO").append('\n');
        }
        return out.toString();
    }

    private String solveConstructive(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt();
        StringBuilder out = new StringBuilder();
        for (int parity = 0; parity <= 1; parity++) {
            for (int i = 1; i <= n; i++) if (i % 2 == parity) {
                if (!out.isEmpty()) out.append(' ');
                out.append(i);
            }
        }
        return out.append('\n').toString();
    }

    private long modPow(long a, long e, long mod) {
        long result = 1 % mod;
        while (e > 0) {
            if ((e & 1) == 1) result = result * a % mod;
            a = a * a % mod;
            e >>= 1;
        }
        return result;
    }

    private String simpleArrayValidatorCode() {
        return """
                import sys
                data=list(map(int,sys.stdin.read().strip().split()))
                if not data: raise SystemExit('missing n')
                n=data[0]
                if not(1<=n<=200): raise SystemExit('n out of range')
                if len(data)!=n+1: raise SystemExit('wrong array length')
                """;
    }

    private String simpleUnweightedGraphValidatorCode(String extraHeaderName) {
        return """
                import sys
                data=list(map(int,sys.stdin.read().strip().split()))
                if len(data)<3: raise SystemExit('missing header')
                n,m,x=data[:3]
                if not(2<=n<=30 and 1<=m<=80 and 1<=x<=n): raise SystemExit('bad header')
                if len(data)!=3+2*m: raise SystemExit('wrong edge count')
                """;
    }

    private String commandArrayValidatorCode(boolean rangeAdd) {
        return """
                import sys
                data=sys.stdin.read().strip().split()
                if len(data)<2: raise SystemExit('missing header')
                i=0
                n=int(data[i]); i+=1
                q=int(data[i]); i+=1
                if not(1<=n<=50 and 1<=q<=80): raise SystemExit('bad header')
                i += n
                for _ in range(q):
                    op=data[i]; i+=1
                    if op=='ADD':
                        need=3 if %s else 2
                        i += need
                    elif op=='SUM':
                        i += 2
                    else:
                        raise SystemExit('bad op')
                if i!=len(data): raise SystemExit('extra tokens')
                """.formatted(rangeAdd);
    }

    private String solveMaxWelterGame(String input) {
        FastScanner fs = new FastScanner(input);
        int t = fs.nextInt();
        StringBuilder out = new StringBuilder();
        while (t-- > 0) {
            int n = fs.nextInt();
            long[] a = new long[n];
            for (int i = 0; i < n; i++) a[i] = fs.nextLong();
            java.util.Arrays.sort(a);
            boolean rr = n == 1
                    ? a[0] == 1
                    : a[n - 1] == a[n - 2] + 1 && Math.floorMod(a[n - 2] + n, 2) == 1;
            out.append(rr ? "RR" : "Hieu").append('\n');
        }
        return out.toString();
    }

    private String solveSubtractionGame(String input) {
        FastScanner fs = new FastScanner(input);
        int t = fs.nextInt();
        StringBuilder out = new StringBuilder();
        while (t-- > 0) {
            out.append(fs.nextLong() % 4 == 0 ? "Second" : "First").append('\n');
        }
        return out.toString();
    }

    private static class FastScanner {
        private final String[] tokens;
        private int index;

        private FastScanner(String input) {
            this.tokens = input.trim().split("\\s+");
        }

        private static FastScanner of(String input) {
            return new FastScanner(input);
        }

        private String next() {
            if (index >= tokens.length) throw new IllegalArgumentException("Unexpected end of input.");
            return tokens[index++];
        }

        private int nextInt() {
            return Integer.parseInt(next());
        }

        private long nextLong() {
            return Long.parseLong(next());
        }
    }

    private Problem generatePermutationRankUnrankProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "Permutation Mapping");
        problem.setDescription("Given a permutation, compute its lexicographical rank. Or given a rank, construct the permutation.");
        problem.setInputFormat("The first line contains q. The next q lines contain RANK n p_1..p_n or UNRANK n k.");
        problem.setOutputFormat("Print the rank or space-separated permutation.");
        problem.setConstraints("Generated judge data uses 1 <= q <= 5 and 1 <= n <= 8.");
        problem.setValidatorCode("""
                import sys
                data = sys.stdin.read().strip().split()
                if not data: raise SystemExit('missing q')
                q = int(data[0])
                if not (1 <= q <= 5): raise SystemExit('bad q')
                i = 1
                for _ in range(q):
                    op = data[i]; i += 1
                    n = int(data[i]); i += 1
                    if not (1 <= n <= 8): raise SystemExit('bad n')
                    if op == 'RANK':
                        arr = list(map(int, data[i:i+n]))
                        i += n
                        if len(set(arr)) != n or any(x < 1 or x > n for x in arr): raise SystemExit('bad permutation')
                    elif op == 'UNRANK':
                        k = int(data[i]); i += 1
                        import math
                        if not (1 <= k <= math.factorial(n)): raise SystemExit('bad rank')
                    else:
                        raise SystemExit('bad op')
                if i != len(data): raise SystemExit('extra tokens')
                """);
        problem.setTestPlan("Seed pattern: PERMUTATION_RANK_UNRANK.");
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std;
                long long fact(int n) { return n <= 1 ? 1 : n * fact(n - 1); }
                int main(){
                    int q; if(!(cin>>q)) return 0;
                    while(q--){
                        string op; cin>>op;
                        int n; cin>>n;
                        if(op=="RANK"){
                            vector<int> p(n);
                            for(int& x:p) cin>>x;
                            vector<int> avail;
                            for(int i=1; i<=n; i++) avail.push_back(i);
                            long long rank = 1;
                            for(int i=0; i<n; i++){
                                int idx = find(avail.begin(), avail.end(), p[i]) - avail.begin();
                                avail.erase(avail.begin() + idx);
                                rank += idx * fact(n - 1 - i);
                            }
                            cout<<rank<<'\\n';
                        } else {
                            long long k; cin>>k;
                            vector<int> avail;
                            for(int i=1; i<=n; i++) avail.push_back(i);
                            vector<int> p;
                            for(int i=n-1; i>=0; i--){
                                int idx = (k - 1) / fact(i);
                                p.push_back(avail[idx]);
                                avail.erase(avail.begin() + idx);
                                k = (k - 1) % fact(i) + 1;
                            }
                            for(int i=0; i<n; i++) cout<<p[i]<<(i==n-1?'\\n':' ');
                        }
                    }
                }
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("mixed_small", 1, "4\nRANK 3\n1 2 3\nUNRANK 3 6\nRANK 4\n2 4 1 3\nUNRANK 4 24\n"),
                new GeneratedTestCase("large_eight", 2, "3\nRANK 8\n8 7 6 5 4 3 2 1\nUNRANK 8 40320\nUNRANK 8 1\n")
        );
        saveGeneratedCases(saved, KnownContracts.permutationRankUnrank(), cases, this::solvePermutationRankUnrank, 1);
        return saved;
    }

    private Problem generateStringKmpCountProblem(String requestedTitle, String source) {
        Problem problem = baseProblem(requestedTitle, "KMP Pattern Occurrences");
        problem.setDescription("Given main string S of size n and pattern string P of size m, count occurrences of P in S.");
        problem.setInputFormat("The first line contains n and m. The second line contains n letter codes. The third line contains m letter codes.");
        problem.setOutputFormat("Print the number of occurrences of P in S.");
        problem.setConstraints("Generated judge data uses 1 <= n <= 50 and 1 <= m <= 20.");
        problem.setValidatorCode("""
                import sys
                data = list(map(int, sys.stdin.read().strip().split()))
                if len(data) < 2: raise SystemExit('missing header')
                n, m = data[:2]
                if not (1 <= n <= 50 and 1 <= m <= 20): raise SystemExit('bad header')
                if len(data) != 2 + n + m: raise SystemExit('bad shape')
                """);
        problem.setTestPlan("Seed pattern: STRING_KMP_COUNT.");
        problem.setAcceptedSolutionCode("""
                #include <bits/stdc++.h>
                using namespace std;
                int main(){
                    int n, m; if(!(cin>>n>>m)) return 0;
                    vector<int> s(n), p(m);
                    for(int& x:s) cin>>x;
                    for(int& x:p) cin>>x;
                    vector<int> pi(m);
                    for(int i=1; i<m; i++){
                        int j = pi[i-1];
                        while(j>0 && p[i]!=p[j]) j = pi[j-1];
                        if(p[i]==p[j]) j++;
                        pi[i] = j;
                    }
                    int count = 0, j = 0;
                    for(int i=0; i<n; i++){
                        while(j>0 && s[i]!=p[j]) j = pi[j-1];
                        if(s[i]==p[j]) j++;
                        if(j==m){
                            count++;
                            j = pi[m-1];
                        }
                    }
                    cout<<count<<'\\n';
                }
                """);
        Problem saved = problemRepository.save(problem);
        List<GeneratedTestCase> cases = List.of(
                new GeneratedTestCase("typical", 1, "10 3\n1 2 1 2 1 2 1 2 3 4\n1 2 1\n"),
                new GeneratedTestCase("overlap", 2, "6 2\n1 1 1 1 1 1\n1 1\n"),
                new GeneratedTestCase("no_match", 3, "5 2\n1 2 3 4 5\n6 7\n")
        );
        saveGeneratedCases(saved, KnownContracts.stringKmpCount(), cases, this::solveStringKmpCount, 1);
        return saved;
    }

    private String solvePermutationRankUnrank(String input) {
        FastScanner fs = new FastScanner(input);
        int q = fs.nextInt();
        StringBuilder out = new StringBuilder();
        long[] fact = new long[11];
        fact[0] = 1;
        for (int i = 1; i <= 10; i++) fact[i] = fact[i - 1] * i;

        while (q-- > 0) {
            String op = fs.next();
            int n = fs.nextInt();
            if ("RANK".equals(op)) {
                int[] p = new int[n];
                for (int i = 0; i < n; i++) p[i] = fs.nextInt();
                java.util.List<Integer> available = new java.util.ArrayList<>();
                for (int i = 1; i <= n; i++) available.add(i);
                long rank = 1;
                for (int i = 0; i < n; i++) {
                    int val = p[i];
                    int idx = available.indexOf(val);
                    available.remove(idx);
                    rank += idx * fact[n - 1 - i];
                }
                out.append(rank).append('\n');
            } else {
                long k = fs.nextLong();
                java.util.List<Integer> available = new java.util.ArrayList<>();
                for (int i = 1; i <= n; i++) available.add(i);
                StringBuilder sb = new StringBuilder();
                for (int i = n - 1; i >= 0; i--) {
                    int idx = (int) ((k - 1) / fact[i]);
                    int val = available.remove(idx);
                    if (!sb.isEmpty()) sb.append(' ');
                    sb.append(val);
                    k = (k - 1) % fact[i] + 1;
                }
                out.append(sb).append('\n');
            }
        }
        return out.toString();
    }

    private String solveStringKmpCount(String input) {
        FastScanner fs = new FastScanner(input);
        int n = fs.nextInt();
        int m = fs.nextInt();
        int[] s = new int[n];
        for (int i = 0; i < n; i++) s[i] = fs.nextInt();
        int[] p = new int[m];
        for (int i = 0; i < m; i++) p[i] = fs.nextInt();

        int[] pi = new int[m];
        for (int i = 1; i < m; i++) {
            int j = pi[i - 1];
            while (j > 0 && p[i] != p[j]) j = pi[j - 1];
            if (p[i] == p[j]) j++;
            pi[i] = j;
        }

        int count = 0;
        int j = 0;
        for (int i = 0; i < n; i++) {
            while (j > 0 && s[i] != p[j]) j = pi[j - 1];
            if (s[i] == p[j]) j++;
            if (j == m) {
                count++;
                j = pi[m - 1];
            }
        }
        return count + "\n";
    }

    private void enrichProblemMetadataWithOriginalDetails(Problem problem, String source) {
        if (source == null || source.isBlank()) return;
        try {
            com.pbj.dto.AiResponseDTO extracted = geminiTestGenerationService.extractVietnameseProblemFields(source);
            if (extracted.getFormattedDescription() != null && !extracted.getFormattedDescription().isBlank()) {
                problem.setDescription(extracted.getFormattedDescription().trim());
            }
            if (extracted.getInputFormat() != null && !extracted.getInputFormat().isBlank()) {
                problem.setInputFormat(extracted.getInputFormat().trim());
            }
            if (extracted.getOutputFormat() != null && !extracted.getOutputFormat().isBlank()) {
                problem.setOutputFormat(extracted.getOutputFormat().trim());
            }
            if (extracted.getConstraints() != null && !extracted.getConstraints().isBlank()) {
                problem.setConstraints(extracted.getConstraints().trim());
            }
            problemRepository.save(problem);
        } catch (Exception e) {
            System.err.println("WARN: Could not enrich problem statement metadata via Gemini: " + e.getMessage());
        }
    }
}
