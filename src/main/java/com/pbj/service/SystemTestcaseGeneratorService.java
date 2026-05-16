package com.pbj.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.pbj.dto.AiResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SystemTestcaseGeneratorService {

    private final ProblemTaxonomyResolver taxonomyResolver;
    private final FallbackGeneratorFactory fallbackGeneratorFactory;

    public String buildGenerator(AiResponseDTO dto, String sourceStatement) {
        ProblemMetadata metadata = taxonomyResolver.resolve(dto, sourceStatement);
        String generator = switch (metadata.type()) {
            case GRAPH_ALTERNATING_EDGE_SHORTEST_PATH -> alternatingEdgeShortestPathGenerator(metadata);
            default -> "";
        };
        if (generator != null && !generator.isBlank()) {
            return generator;
        }

        List<String> candidates = fallbackGeneratorFactory.createCandidates(dto);
        return candidates.isEmpty() ? "" : candidates.get(0);
    }

    private String alternatingEdgeShortestPathGenerator(ProblemMetadata metadata) {
        EdgeSpec edge = extractEdgeSpec(metadata.inputSchema());
        long nMin = scalarBound(metadata.inputSchema(), "N", true, 2L);
        long nMax = scalarBound(metadata.inputSchema(), "N", false, 100_000L);
        long mMin = scalarBound(metadata.inputSchema(), "M", true, 1L);
        long mMax = scalarBound(metadata.inputSchema(), "M", false, 200_000L);
        long wMin = Math.max(1L, edge.weightMin());
        long wMax = Math.max(wMin, edge.weightMax());
        boolean directed = edge.directed();
        boolean allowSelfLoop = edge.selfLoopAllowed();
        boolean allowMultiEdge = edge.multiEdgeAllowed();

        return """
                #include <bits/stdc++.h>
                using namespace std;

                long long clampValue(long long value, long long lo, long long hi) {
                    if (hi < lo) hi = lo;
                    return max(lo, min(value, hi));
                }

                struct Edge {
                    int u, v;
                    long long w;
                    int type;
                };

                string bucketForProfile(string profile) {
                    for (char& ch : profile) ch = (char)tolower(ch);
                    if (profile == "random_small" || profile == "anti_greedy_small"
                            || profile == "tie_breaking" || profile == "edge_boundary") return "small";
                    if (profile == "medium") return "medium";
                    if (profile == "random_large" || profile == "adversarial_structure") return "large";
                    if (profile == "stress_performance" || profile == "overflow_int32"
                            || profile == "overflow_int64_if_relevant") return "stress";
                    return "small";
                }

                int chooseN(const string& profile, const string& bucket, int seed) {
                    long long n = 6;
                    if (profile == "edge_boundary") n = 2 + (seed %% 3);
                    else if (profile == "anti_greedy_small" || profile == "tie_breaking") n = 5 + (seed %% 4);
                    else if (bucket == "medium") n = 100 + (seed %% 900);
                    else if (bucket == "large") n = 5000 + (seed %% 15000);
                    else if (bucket == "stress") n = %dLL;
                    return (int)clampValue(n, %dLL, %dLL);
                }

                long long chooseW(const string& profile, int i, mt19937& rng) {
                    long long lo = %dLL, hi = %dLL;
                    if (profile == "overflow_int32" || profile == "overflow_int64_if_relevant") {
                        return hi;
                    }
                    if (profile == "tie_breaking") return (i %% 2 == 0) ? 5 : 6;
                    if (profile == "anti_greedy_small") {
                        static long long vals[] = {1, 100, 2, 2, 2, 50, 3, 3};
                        return clampValue(vals[i %% 8], lo, hi);
                    }
                    unsigned long long span = (unsigned long long)(hi - lo + 1);
                    return lo + (long long)(rng() %% span);
                }

                void addEdge(vector<Edge>& edges, int u, int v, long long w, int type) {
                    if (!%s && u == v) v = (v %% max(1, u + 1)) + 1;
                    edges.push_back({u, v, w, type & 1});
                }

                void addChain(vector<Edge>& edges, int n, const string& profile, mt19937& rng) {
                    for (int i = 1; i < n; i++) {
                        addEdge(edges, i, i + 1, chooseW(profile, i, rng), i %% 2);
                    }
                }

                int main(int argc, char** argv) {
                    int seed = argc > 1 ? stoi(argv[1]) : 1;
                    string profile = argc > 2 ? argv[2] : "random_small";
                    string bucket = bucketForProfile(profile);
                    mt19937 rng(seed);

                    int n = chooseN(profile, bucket, seed);
                    vector<Edge> edges;

                    if (profile == "edge_boundary") {
                        addEdge(edges, 1, n, chooseW(profile, 0, rng), seed & 1);
                    } else if (profile == "anti_greedy_small") {
                        n = (int)clampValue(5 + seed %% 2, %dLL, %dLL);
                        addEdge(edges, 1, 2, 1, 0);
                        addEdge(edges, 2, n, 100, 0);
                        addEdge(edges, 1, 3, 2, 1);
                        addEdge(edges, 3, 4, 2, 0);
                        addEdge(edges, 4, n, 2, 1);
                    } else if (profile == "tie_breaking") {
                        n = (int)clampValue(6, %dLL, %dLL);
                        addEdge(edges, 1, 2, 5, 0);
                        addEdge(edges, 1, 3, 5, 0);
                        addEdge(edges, 2, n, 100, 1);
                        addEdge(edges, 3, 4, 6, 1);
                        addEdge(edges, 4, n, 6, 0);
                    } else {
                        addChain(edges, n, profile, rng);
                        if (bucket != "small") {
                            int extra = bucket == "stress" ? min((long long)%d, max(0LL, %dLL - (long long)edges.size())) : min(3 * n, 20000);
                            for (int i = 0; i < extra; i++) {
                                int u = 1 + (int)(rng() %% n);
                                int v = 1 + (int)(rng() %% n);
                                if (!%s && u == v) v = (v %% n) + 1;
                                long long w = chooseW(profile, i + n, rng);
                                int type = (i + seed) & 1;
                                addEdge(edges, u, v, w, type);
                            }
                        }
                    }

                    long long m = clampValue((long long)edges.size(), %dLL, %dLL);
                    while ((long long)edges.size() < m) {
                        int u = 1 + (int)(rng() %% n);
                        int v = 1 + (int)(rng() %% n);
                        if (!%s && u == v) v = (v %% n) + 1;
                        addEdge(edges, u, v, chooseW(profile, (int)edges.size(), rng), (int)edges.size());
                    }
                    if ((long long)edges.size() > m) edges.resize((size_t)m);

                    cout << n << ' ' << edges.size() << "\\n";
                    for (const Edge& e : edges) {
                        cout << e.u << ' ' << e.v << ' ' << e.w << ' ' << e.type << "\\n";
                    }
                    return 0;
                }
                """.formatted(
                Math.min(nMax, 100_000L), nMin, nMax, wMin, wMax,
                cppBool(allowSelfLoop),
                nMin, nMax, nMin, nMax,
                Math.max(0L, mMax - Math.max(0L, nMax - 1L)), mMax,
                cppBool(allowSelfLoop),
                mMin, mMax,
                cppBool(allowSelfLoop));
    }

    private EdgeSpec extractEdgeSpec(JsonNode schema) {
        if (schema == null || !schema.path("lines").isArray()) {
            return new EdgeSpec(false, false, true, 1L, 1_000_000_000L);
        }

        for (JsonNode line : schema.path("lines")) {
            if (!"edges".equalsIgnoreCase(line.path("kind").asText(""))) continue;
            JsonNode columns = line.path("columns");
            long wMin = 1L;
            long wMax = 1_000_000_000L;
            if (columns.isArray()) {
                for (JsonNode column : columns) {
                    String name = column.path("name").asText("").toLowerCase(Locale.ROOT);
                    if (name.equals("w") || name.equals("weight") || name.equals("cost")) {
                        wMin = numericBound(column.path("min"), 1L);
                        wMax = numericBound(column.path("max"), 1_000_000_000L);
                    }
                }
            }
            return new EdgeSpec(
                    line.path("directed").asBoolean(false),
                    line.path("self_loop_allowed").asBoolean(false),
                    line.path("multi_edge_allowed").asBoolean(true),
                    wMin,
                    wMax);
        }

        return new EdgeSpec(false, false, true, 1L, 1_000_000_000L);
    }

    private long scalarBound(JsonNode schema, String scalarName, boolean min, long fallback) {
        if (schema == null || !schema.path("lines").isArray()) return fallback;
        for (JsonNode line : schema.path("lines")) {
            if (!"scalars".equalsIgnoreCase(line.path("kind").asText(""))) continue;
            JsonNode fields = line.path("fields");
            if (!fields.isArray()) continue;
            for (JsonNode field : fields) {
                if (scalarName.equalsIgnoreCase(field.path("name").asText(""))) {
                    return numericBound(field.path(min ? "min" : "max"), fallback);
                }
            }
        }
        return fallback;
    }

    private long numericBound(JsonNode node, long fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) return fallback;
        if (node.isNumber()) return node.asLong();
        String text = node.asText("").toLowerCase(Locale.ROOT).trim()
                .replace(" ", "")
                .replace(",", "");
        if (text.matches("-?\\d+")) return Long.parseLong(text);
        if (text.matches("-?10\\^\\d+")) return pow10(text, text.startsWith("-") ? 4 : 3);
        if (text.matches("-?1e\\d+")) return pow10(text, text.startsWith("-") ? 3 : 2);
        return fallback;
    }

    private long pow10(String text, int start) {
        boolean negative = text.startsWith("-");
        int power = Integer.parseInt(text.substring(start));
        long value = 1L;
        for (int i = 0; i < power && value < 1_000_000_000L; i++) value *= 10L;
        return negative ? -value : value;
    }

    private String cppBool(boolean value) {
        return value ? "true" : "false";
    }

    private record EdgeSpec(boolean directed,
                            boolean selfLoopAllowed,
                            boolean multiEdgeAllowed,
                            long weightMin,
                            long weightMax) {}
}
