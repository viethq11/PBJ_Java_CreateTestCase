package com.pbj.service;

import com.pbj.dto.AiResponseDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class FallbackGeneratorFactory {

    public List<String> createCandidates(AiResponseDTO dto) {
        String input = normalized(dto == null ? null : dto.getInputFormat());
        String constraints = normalized(dto == null ? null : dto.getConstraints());
        String combined = input + "\n" + constraints;

        List<String> candidates = new ArrayList<>();
        if (looksLikeGraph(input)) {
            candidates.add(graphGenerator(edgeColumns(input), hasBinaryEdgeType(input), hasWeights(input)));
        }
        if (looksLikeTree(input)) {
            candidates.add(treeGenerator());
        }
        if (looksLikeArrayWithK(input)) {
            candidates.add(arrayWithKGenerator(maxValue(combined)));
        }
        if (looksLikeArray(input)) {
            candidates.add(arrayOnlyGenerator(maxValue(combined)));
        }

        candidates.add(arrayOnlyGenerator(1_000_000_000));
        candidates.add(graphGenerator(2, false, false));
        return candidates;
    }

    private boolean looksLikeGraph(String input) {
        return input.contains(" u ") && input.contains(" v ")
                || input.contains("u v")
                || input.contains("edge")
                || input.contains("canh")
                || input.contains("m dong")
                || input.contains("m lines");
    }

    private boolean looksLikeTree(String input) {
        return input.contains("tree") || input.contains("cay") || input.contains("n-1");
    }

    private boolean looksLikeArrayWithK(String input) {
        return looksLikeArray(input) && (input.contains(" k ") || input.contains(" n k") || input.contains("n, k"));
    }

    private boolean looksLikeArray(String input) {
        return input.contains("array")
                || input.contains("mang")
                || input.contains("day")
                || input.contains("a_i")
                || input.contains("a[")
                || input.contains(" a ");
    }

    private int edgeColumns(String input) {
        if (input.contains("u v w") || input.contains("u, v, w")) return 3;
        if (input.contains("u v t") || input.contains("u, v, t")) return 3;
        if (input.contains("u v c") || input.contains("u, v, c")) return 3;
        return 2;
    }

    private boolean hasBinaryEdgeType(String input) {
        return input.contains("0 or 1")
                || input.contains("0 hoac 1")
                || input.contains("0 hoặc 1")
                || input.contains("binary");
    }

    private boolean hasWeights(String input) {
        return input.contains("weight")
                || input.contains("trong so")
                || input.contains("trọng số")
                || input.contains(" w ");
    }

    private long maxValue(String text) {
        if (text.contains("10^9") || text.contains("1e9") || text.contains("1000000000")) return 1_000_000_000L;
        if (text.contains("10^6") || text.contains("1e6") || text.contains("1000000")) return 1_000_000L;
        return 100_000L;
    }

    private String normalized(String text) {
        if (text == null) return "";
        return " " + text.toLowerCase(Locale.ROOT)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("[,.;:()]+", " ")
                .replaceAll("\\s+", " ")
                .trim() + " ";
    }

    private String arrayOnlyGenerator(long maxValue) {
        return """
                #include <bits/stdc++.h>
                using namespace std;
                int main(int argc, char** argv) {
                    int seed = argc > 1 ? stoi(argv[1]) : 1;
                    string size = argc > 2 ? argv[2] : "small";
                    mt19937 rng(seed);
                    int n = 5;
                    if (size == "medium") n = 1000;
                    else if (size == "large") n = 100000;
                    else if (size == "stress") n = 200000;
                    long long hi = %dLL;
                    cout << n << "\\n";
                    for (int i = 0; i < n; i++) {
                        if (i) cout << ' ';
                        long long x;
                        if (size == "stress" && i %% 3 == 0) x = hi;
                        else x = 1 + (long long)(rng() %% hi);
                        cout << x;
                    }
                    cout << "\\n";
                    return 0;
                }
                """.formatted(maxValue);
    }

    private String arrayWithKGenerator(long maxValue) {
        return """
                #include <bits/stdc++.h>
                using namespace std;
                int main(int argc, char** argv) {
                    int seed = argc > 1 ? stoi(argv[1]) : 1;
                    string size = argc > 2 ? argv[2] : "small";
                    mt19937 rng(seed);
                    int n = 5;
                    if (size == "medium") n = 1000;
                    else if (size == "large") n = 100000;
                    else if (size == "stress") n = 200000;
                    int k = max(1, min(n, size == "small" ? 2 : n / 2));
                    long long hi = %dLL;
                    cout << n << ' ' << k << "\\n";
                    for (int i = 0; i < n; i++) {
                        if (i) cout << ' ';
                        long long x = (size == "stress" && i %% 2 == 0) ? hi : 1 + (long long)(rng() %% hi);
                        cout << x;
                    }
                    cout << "\\n";
                    return 0;
                }
                """.formatted(maxValue);
    }

    private String treeGenerator() {
        return """
                #include <bits/stdc++.h>
                using namespace std;
                int main(int argc, char** argv) {
                    int seed = argc > 1 ? stoi(argv[1]) : 1;
                    string size = argc > 2 ? argv[2] : "small";
                    mt19937 rng(seed);
                    int n = 5;
                    if (size == "medium") n = 1000;
                    else if (size == "large") n = 100000;
                    else if (size == "stress") n = 200000;
                    cout << n << "\\n";
                    for (int i = 2; i <= n; i++) {
                        int parent;
                        if (seed % 3 == 0) parent = 1;
                        else if (seed % 3 == 1) parent = i - 1;
                        else parent = 1 + (int)(rng() % (i - 1));
                        cout << parent << ' ' << i << "\\n";
                    }
                    return 0;
                }
                """;
    }

    private String graphGenerator(int columns, boolean binaryType, boolean weighted) {
        String thirdValue = binaryType ? "(i % 2)" : (weighted ? "(1 + (int)(rng() % 1000000000))" : "1");
        return """
                #include <bits/stdc++.h>
                using namespace std;
                int main(int argc, char** argv) {
                    int seed = argc > 1 ? stoi(argv[1]) : 1;
                    string size = argc > 2 ? argv[2] : "small";
                    mt19937 rng(seed);
                    int n = 5;
                    if (size == "medium") n = 1000;
                    else if (size == "large") n = 100000;
                    else if (size == "stress") n = 200000;
                    int m = min(n - 1, 200000);
                    int shift = seed %% n;
                    cout << n << ' ' << m << "\\n";
                    for (int i = 1; i <= m; i++) {
                        int u = ((i + shift - 1) %% n) + 1;
                        int v = ((i + shift) %% n) + 1;
                        if (%d == 3) cout << u << ' ' << v << ' ' << %s << "\\n";
                        else cout << u << ' ' << v << "\\n";
                    }
                    return 0;
                }
                """.formatted(columns, thirdValue);
    }
}
