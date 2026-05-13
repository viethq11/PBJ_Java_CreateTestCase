package com.pbj.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.pbj.dto.AiResponseDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class FallbackGeneratorFactory {

    public List<String> createCandidates(AiResponseDTO dto) {
        String input = normalized(dto == null ? null : dto.getInputFormat());
        String constraints = normalized(dto == null ? null : dto.getConstraints());
        String combined = input + "\n" + constraints;
        long maxCount = maxCount(combined);

        List<String> candidates = new ArrayList<>();
        String schemaCandidate = schemaDrivenGenerator(dto == null ? null : dto.getInputSchema());
        if (!schemaCandidate.isBlank()) {
            candidates.add(schemaCandidate);
        }

        if (looksLikeTwoHeaderWithDPArrays(input)) {
            candidates.add(twoHeaderTwoArraysGenerator(maxValue(combined), maxCount));
        }
        if (looksLikeTwoHeaderWithArrays(input)) {
            candidates.add(twoHeaderTwoArraysGenerator(maxValue(combined), maxCount));
        }
        if (looksLikeGraph(input)) {
            candidates.add(graphGenerator(edgeColumns(input), hasBinaryEdgeType(input), hasWeights(input), maxCount));
        }
        if (looksLikeTree(input)) {
            candidates.add(treeGenerator(maxCount));
        }
        if (looksLikeArrayWithK(input)) {
            candidates.add(arrayWithKGenerator(maxValue(combined), maxCount));
        }
        if (looksLikeArray(input)) {
            candidates.add(arrayOnlyGenerator(maxValue(combined), maxCount));
        }

        if (candidates.isEmpty()) {
            candidates.add(arrayOnlyGenerator(1_000_000_000, 200_000L));
            candidates.add(graphGenerator(2, false, false, 200_000L));
        }
        return candidates;
    }

    private String schemaDrivenGenerator(JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) return "";

        JsonNode lines = schema.path("lines");
        if (!lines.isArray() || lines.isEmpty()) return "";

        Set<String> edgeLengthRefs = collectEdgeLengthRefs(lines);
        StringBuilder body = new StringBuilder();
        boolean supportedAnyLine = false;

        for (JsonNode line : lines) {
            String kind = line.path("kind").asText("").toLowerCase(Locale.ROOT);
            if ("scalars".equals(kind)) {
                if (appendScalarLine(body, line, edgeLengthRefs)) supportedAnyLine = true;
            } else if ("array".equals(kind)) {
                if (appendArrayLine(body, line)) supportedAnyLine = true;
            } else if ("matrix".equals(kind)) {
                if (appendMatrixLine(body, line)) supportedAnyLine = true;
            } else if ("grid".equals(kind)) {
                if (appendGridLine(body, line)) supportedAnyLine = true;
            } else if ("edges".equals(kind) || "queries".equals(kind)) {
                if (appendTupleLine(body, line)) supportedAnyLine = true;
            } else if ("string".equals(kind)) {
                if (appendStringLine(body, line)) supportedAnyLine = true;
            }
        }

        if (!supportedAnyLine) return "";

        return """
                #include <bits/stdc++.h>
                using namespace std;

                long long clampValue(long long value, long long lo, long long hi) {
                    if (hi < lo) hi = lo;
                    return max(lo, min(value, hi));
                }

                long long chooseCount(const string& size, long long lo, long long hi) {
                    if (hi < lo) hi = lo;
                    long long target = 5;
                    if (size == "medium") target = 1000;
                    else if (size == "large") target = 100000;
                    else if (size == "stress") target = 200000;
                    return clampValue(target, lo, hi);
                }

                long long chooseValue(mt19937& rng, const string& size, long long lo, long long hi, long long i) {
                    if (hi < lo) hi = lo;
                    if (size == "stress") {
                        if (i %% 5 == 0) return hi;
                        if (i %% 5 == 1) return lo;
                    }
                    unsigned long long span = (unsigned long long)(hi - lo + 1);
                    return lo + (long long)(rng() %% span);
                }

                int main(int argc, char** argv) {
                    int seed = argc > 1 ? stoi(argv[1]) : 1;
                    string size = argc > 2 ? argv[2] : "small";
                    mt19937 rng(seed);
                    unordered_map<string, long long> vars;
                    auto getVar = [&](const string& name, long long fallback) -> long long {
                        auto it = vars.find(name);
                        if (it != vars.end()) return it->second;
                        string lower = name;
                        for (char& c : lower) c = (char)tolower(c);
                        it = vars.find(lower);
                        if (it != vars.end()) return it->second;
                        return fallback;
                    };

                %s
                    return 0;
                }
                """.formatted(indent(body.toString(), "    "));
    }

    private Set<String> collectEdgeLengthRefs(JsonNode lines) {
        Set<String> refs = new HashSet<>();
        for (JsonNode line : lines) {
            String kind = line.path("kind").asText("").toLowerCase(Locale.ROOT);
            if ("edges".equals(kind)
                    && !line.path("multi_edge_allowed").asBoolean(true)
                    && countNodeColumns(line.path("columns")) >= 2) {
                String length = line.path("length").asText("");
                if (isIdentifier(length)) refs.add(length);
            }
        }
        return refs;
    }

    private boolean appendScalarLine(StringBuilder body, JsonNode line, Set<String> edgeLengthRefs) {
        JsonNode fields = line.path("fields");
        if (!fields.isArray() || fields.isEmpty()) return false;

        List<String> names = new ArrayList<>();
        for (JsonNode field : fields) {
            String name = sanitizeIdentifier(field.path("name").asText("x" + names.size()));
            long lo = numericBound(field.path("min"), 1L);
            long hi = Math.min(numericBound(field.path("max"), 200_000L), 1_000_000_000L);
            if (hi < lo) hi = lo;

            String lowerName = name.toLowerCase(Locale.ROOT);
            boolean countLike = lowerName.matches("[nmkqtr]|.*count|.*size|.*len|.*length");
            String expression = countLike
                    ? "chooseCount(size, " + lo + "LL, " + hi + "LL)"
                    : "chooseValue(rng, size, " + lo + "LL, " + hi + "LL, seed)";

            if (edgeLengthRefs.contains(name) && !names.isEmpty()) {
                String nodeVar = names.get(0);
                expression = "clampValue(" + expression + ", " + lo + "LL, max(" + lo + "LL, min(" + hi + "LL, " + nodeVar + " - 1)))";
            }

            body.append("long long ").append(name).append(" = ").append(expression).append(";\n");
            body.append("vars[\"").append(name).append("\"] = ").append(name).append(";\n");
            body.append("vars[\"").append(lowerName).append("\"] = ").append(name).append(";\n");
            names.add(name);
        }

        body.append("cout");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) body.append(" << ' '");
            body.append(" << ").append(names.get(i));
        }
        body.append(" << \"\\n\";\n");
        return true;
    }

    private boolean appendArrayLine(StringBuilder body, JsonNode line) {
        String lengthExpr = lengthExpression(line.path("length"), 5L);
        long lo = numericBound(line.path("min"), 1L);
        long hi = Math.min(numericBound(line.path("max"), 1_000_000_000L), 1_000_000_000L);
        if (hi < lo) hi = lo;

        body.append("{\n");
        body.append("    long long len = clampValue(").append(lengthExpr).append(", 0LL, 200000LL);\n");
        body.append("    for (long long i = 0; i < len; i++) {\n");
        body.append("        if (i) cout << ' ';\n");
        body.append("        cout << chooseValue(rng, size, ").append(lo).append("LL, ").append(hi).append("LL, i);\n");
        body.append("    }\n");
        body.append("    cout << \"\\n\";\n");
        body.append("}\n");
        return true;
    }

    private boolean appendMatrixLine(StringBuilder body, JsonNode line) {
        String rows = lengthExpression(firstPresent(line, "rows", "height", "n"), 5L);
        String cols = lengthExpression(firstPresent(line, "cols", "columns", "width", "m"), 5L);
        long lo = numericBound(line.path("min"), 0L);
        long hi = Math.min(numericBound(line.path("max"), 1_000_000_000L), 1_000_000_000L);
        if (hi < lo) hi = lo;

        body.append("{\n");
        body.append("    long long rows = clampValue(").append(rows).append(", 1LL, 2000LL);\n");
        body.append("    long long cols = clampValue(").append(cols).append(", 1LL, 2000LL);\n");
        body.append("    for (long long r = 0; r < rows; r++) {\n");
        body.append("        for (long long c = 0; c < cols; c++) {\n");
        body.append("            if (c) cout << ' ';\n");
        body.append("            cout << chooseValue(rng, size, ").append(lo).append("LL, ").append(hi).append("LL, r * cols + c);\n");
        body.append("        }\n");
        body.append("        cout << \"\\n\";\n");
        body.append("    }\n");
        body.append("}\n");
        return true;
    }

    private boolean appendGridLine(StringBuilder body, JsonNode line) {
        String rows = lengthExpression(firstPresent(line, "rows", "height", "n"), 5L);
        String cols = lengthExpression(firstPresent(line, "cols", "columns", "width", "m"), 5L);
        String alphabet = gridAlphabet(line);

        body.append("{\n");
        body.append("    string alphabet = \"").append(alphabet).append("\";\n");
        body.append("    long long rows = clampValue(").append(rows).append(", 1LL, 2000LL);\n");
        body.append("    long long cols = clampValue(").append(cols).append(", 1LL, 2000LL);\n");
        body.append("    for (long long r = 0; r < rows; r++) {\n");
        body.append("        for (long long c = 0; c < cols; c++) {\n");
        body.append("            size_t idx = (rng() + r * 131 + c * 17) % alphabet.size();\n");
        body.append("            if (size == \"stress\" && alphabet.size() > 1 && (r + c) % 7 == 0) idx = alphabet.size() - 1;\n");
        body.append("            cout << alphabet[idx];\n");
        body.append("        }\n");
        body.append("        cout << \"\\n\";\n");
        body.append("    }\n");
        body.append("}\n");
        return true;
    }

    private boolean appendTupleLine(StringBuilder body, JsonNode line) {
        JsonNode columns = line.path("columns");
        if (!columns.isArray() || columns.isEmpty()) return false;

        String kind = line.path("kind").asText("").toLowerCase(Locale.ROOT);
        String lengthExpr = lengthExpression(line.path("length"), 5L);
        boolean selfLoopAllowed = line.path("self_loop_allowed").asBoolean(false);
        boolean multiEdgeAllowed = line.path("multi_edge_allowed").asBoolean(true);
        boolean edgeLike = "edges".equals(kind) && countNodeColumns(columns) >= 2;

        body.append("{\n");
        body.append("    long long cnt = clampValue(").append(lengthExpr).append(", 0LL, 200000LL);\n");
        body.append("    long long nodeMax = max(1LL, getVar(\"N\", getVar(\"n\", 200000LL)));\n");
        if (edgeLike && !multiEdgeAllowed) {
            body.append("    cnt = min(cnt, max(0LL, nodeMax - 1));\n");
        }
        body.append("    for (long long i = 0; i < cnt; i++) {\n");

        for (int c = 0; c < columns.size(); c++) {
            JsonNode column = columns.get(c);
            String type = column.path("type").asText("int").toLowerCase(Locale.ROOT);
            String expr;
            if ("node".equals(type) || "vertex".equals(type)) {
                if (c == 0) {
                    expr = "((i % nodeMax) + 1)";
                } else {
                    expr = "(((i + " + c + ") % nodeMax) + 1)";
                    if (!selfLoopAllowed) {
                        expr = "(((" + expr + ") == ((i % nodeMax) + 1)) ? ((((i + " + c + ") % nodeMax) + 1) % nodeMax + 1) : (" + expr + "))";
                    }
                }
            } else {
                long lo = numericBound(column.path("min"), 1L);
                long hi = Math.min(numericBound(column.path("max"), 1_000_000_000L), 1_000_000_000L);
                if (hi < lo) hi = lo;
                if (isBinaryColumn(column)) {
                    lo = 0L;
                    hi = 1L;
                }
                expr = "chooseValue(rng, size, " + lo + "LL, " + hi + "LL, i + " + c + ")";
            }

            if (c > 0) body.append("        cout << ' ';\n");
            body.append("        cout << ").append(expr).append(";\n");
        }

        body.append("        cout << \"\\n\";\n");
        body.append("    }\n");
        body.append("}\n");
        return true;
    }

    private int countNodeColumns(JsonNode columns) {
        if (columns == null || !columns.isArray()) return 0;
        int count = 0;
        for (JsonNode column : columns) {
            String type = column.path("type").asText("").toLowerCase(Locale.ROOT);
            if ("node".equals(type) || "vertex".equals(type)) count++;
        }
        return count;
    }

    private boolean appendStringLine(StringBuilder body, JsonNode line) {
        String lengthExpr = lengthExpression(line.path("length"), 5L);
        String alphabet = gridAlphabet(line);

        body.append("{\n");
        body.append("    string alphabet = \"").append(alphabet).append("\";\n");
        body.append("    long long len = clampValue(").append(lengthExpr).append(", 1LL, 200000LL);\n");
        body.append("    for (long long i = 0; i < len; i++) cout << alphabet[(rng() + i) % alphabet.size()];\n");
        body.append("    cout << \"\\n\";\n");
        body.append("}\n");
        return true;
    }

    private String gridAlphabet(JsonNode line) {
        String alphabet = firstText(line, "alphabet", "chars", "symbols", "domain");
        String normalized = alphabet.toLowerCase(Locale.ROOT).replace(" ", "");
        if (alphabet.isBlank()) {
            alphabet = "01";
        } else if (normalized.contains("0") && normalized.contains("1") && !normalized.matches(".*[2-9].*")) {
            alphabet = "01";
        } else if (alphabet.contains("#") || alphabet.contains(".")) {
            alphabet = ".#";
        } else if (normalized.contains("lowercase") || normalized.contains("letter")) {
            alphabet = "abc";
        }
        alphabet = alphabet.replace("\\", "").replace("\"", "");
        if (alphabet.isBlank()) alphabet = "01";
        return alphabet;
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode candidate = node.path(name);
            if (!candidate.isMissingNode() && !candidate.isNull()) {
                String value = candidate.asText("").trim();
                if (!value.isBlank()) return value;
            }
        }
        return "";
    }

    private JsonNode firstPresent(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode candidate = node.path(name);
            if (!candidate.isMissingNode() && !candidate.isNull()) return candidate;
        }
        return node.path("missing");
    }

    private String lengthExpression(JsonNode node, long fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) return fallback + "LL";
        if (node.isNumber()) return Math.max(0L, node.asLong()) + "LL";
        String text = node.asText("").trim();
        if (text.isBlank()) return fallback + "LL";
        if (text.matches("\\d+")) return text + "LL";
        if (text.matches("[A-Za-z_][A-Za-z0-9_]*")) return "getVar(\"" + text + "\", " + fallback + "LL)";
        if (text.matches("[A-Za-z_][A-Za-z0-9_]*\\s*-\\s*\\d+")) {
            String[] parts = text.split("-");
            return "max(0LL, getVar(\"" + parts[0].trim() + "\", " + fallback + "LL) - " + parts[1].trim() + "LL)";
        }
        return fallback + "LL";
    }

    private long numericBound(JsonNode node, long fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) return fallback;
        if (node.isNumber()) return node.asLong();
        String text = node.asText("").toLowerCase(Locale.ROOT).trim()
                .replace(" ", "")
                .replace(",", "");
        if (text.isBlank()) return fallback;
        if (text.matches("-?\\d+")) return Long.parseLong(text);
        if (text.matches("-?10\\^\\d+")) {
            boolean neg = text.startsWith("-");
            int power = Integer.parseInt(text.substring(neg ? 4 : 3));
            long value = 1L;
            for (int i = 0; i < power && value < 1_000_000_000L; i++) value *= 10L;
            return neg ? -value : value;
        }
        if (text.matches("-?1e\\d+")) {
            boolean neg = text.startsWith("-");
            int power = Integer.parseInt(text.substring(neg ? 3 : 2));
            long value = 1L;
            for (int i = 0; i < power && value < 1_000_000_000L; i++) value *= 10L;
            return neg ? -value : value;
        }
        return fallback;
    }

    private boolean isBinaryColumn(JsonNode column) {
        String name = column.path("name").asText("").toLowerCase(Locale.ROOT);
        String domain = column.path("domain").asText("").toLowerCase(Locale.ROOT);
        return name.equals("t") || name.equals("type") || domain.contains("0") && domain.contains("1");
    }

    private boolean isIdentifier(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private String sanitizeIdentifier(String value) {
        String sanitized = value == null ? "" : value.replaceAll("[^A-Za-z0-9_]", "_");
        if (sanitized.isBlank()) sanitized = "x";
        if (!Character.isLetter(sanitized.charAt(0)) && sanitized.charAt(0) != '_') {
            sanitized = "x_" + sanitized;
        }
        return sanitized;
    }

    private String indent(String text, String prefix) {
        if (text == null || text.isBlank()) return "";
        return prefix + text.replace("\n", "\n" + prefix);
    }

    private boolean looksLikeGraph(String input) {
        return input.contains(" u ") && input.contains(" v ")
                || input.contains("u v")
                || input.contains("edge")
                || input.contains("canh");
    }

    private boolean looksLikeTree(String input) {
        return input.contains("tree") || input.contains("cay") || input.contains("n-1");
    }

    private boolean looksLikeArrayWithK(String input) {
        return looksLikeArray(input) && (input.contains(" k ") || input.contains(" n k") || input.contains("n, k"));
    }

    private boolean looksLikeTwoHeaderWithDPArrays(String input) {
        return looksLikeTwoScalarHeader(input)
                && (input.contains(" d ") || input.contains("d[") || input.contains("d_i"))
                && (input.contains(" p ") || input.contains("p[") || input.contains("p_i"));
    }

    private boolean looksLikeTwoHeaderWithArrays(String input) {
        return looksLikeTwoScalarHeader(input)
                && looksLikeArray(input)
                && !looksLikeGraph(input);
    }

    private boolean looksLikeTwoScalarHeader(String input) {
        return input.contains(" n m ")
                || input.contains("n m")
                || input.contains("n, m")
                || input.contains(" n va m ")
                || input.contains(" n và m ")
                || input.contains(" n and m ");
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

    private long maxCount(String text) {
        if (text.contains("2*10^5") || text.contains("2e5") || text.contains("200000")) return 200_000L;
        if (text.contains("10^5") || text.contains("1e5") || text.contains("100000")) return 100_000L;
        return 200_000L;
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

    private String arrayOnlyGenerator(long maxValue, long maxCount) {
        return """
                #include <bits/stdc++.h>
                using namespace std;
                int main(int argc, char** argv) {
                    int seed = argc > 1 ? stoi(argv[1]) : 1;
                    string size = argc > 2 ? argv[2] : "small";
                    mt19937 rng(seed);
                    int limit = %d;
                    int n = min(5, limit);
                    if (size == "medium") n = min(1000, limit);
                    else if (size == "large") n = min(100000, limit);
                    else if (size == "stress") n = limit;
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
                """.formatted(maxCount, maxValue);
    }

    private String arrayWithKGenerator(long maxValue, long maxCount) {
        return """
                #include <bits/stdc++.h>
                using namespace std;
                int main(int argc, char** argv) {
                    int seed = argc > 1 ? stoi(argv[1]) : 1;
                    string size = argc > 2 ? argv[2] : "small";
                    mt19937 rng(seed);
                    int limit = %d;
                    int n = min(5, limit);
                    if (size == "medium") n = min(1000, limit);
                    else if (size == "large") n = min(100000, limit);
                    else if (size == "stress") n = limit;
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
                """.formatted(maxCount, maxValue);
    }

    private String twoHeaderTwoArraysGenerator(long maxValue, long maxCount) {
        return """
                #include <bits/stdc++.h>
                using namespace std;
                int main(int argc, char** argv) {
                    int seed = argc > 1 ? stoi(argv[1]) : 1;
                    string size = argc > 2 ? argv[2] : "small";
                    mt19937 rng(seed);
                    int limit = %d;
                    int n = min(5, limit);
                    if (size == "medium") n = min(1000, limit);
                    else if (size == "large") n = min(100000, limit);
                    else if (size == "stress") n = limit;
                    int m = n;
                    if (size != "stress") m = max(1, min(limit, n + (seed %% max(1, n))));
                    long long hi = %dLL;
                    cout << n << ' ' << m << "\\n";
                    for (int i = 0; i < n; i++) {
                        if (i) cout << ' ';
                        long long x = (size == "stress" && i %% 3 == 0) ? hi : 1 + (long long)(rng() %% hi);
                        cout << x;
                    }
                    cout << "\\n";
                    for (int i = 0; i < m; i++) {
                        if (i) cout << ' ';
                        long long x = (size == "stress" && i %% 5 == 0) ? hi : 1 + (long long)(rng() %% hi);
                        cout << x;
                    }
                    cout << "\\n";
                    return 0;
                }
                """.formatted(maxCount, maxValue);
    }

    private String treeGenerator(long maxCount) {
        return """
                #include <bits/stdc++.h>
                using namespace std;
                int main(int argc, char** argv) {
                    int seed = argc > 1 ? stoi(argv[1]) : 1;
                    string size = argc > 2 ? argv[2] : "small";
                    mt19937 rng(seed);
                    int limit = %d;
                    int n = min(5, limit);
                    if (size == "medium") n = min(1000, limit);
                    else if (size == "large") n = min(100000, limit);
                    else if (size == "stress") n = limit;
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
                """.formatted(maxCount);
    }

    private String graphGenerator(int columns, boolean binaryType, boolean weighted, long maxCount) {
        String thirdValue = binaryType ? "(i % 2)" : (weighted ? "(1 + (int)(rng() % 1000000000))" : "1");
        return """
                #include <bits/stdc++.h>
                using namespace std;
                int main(int argc, char** argv) {
                    int seed = argc > 1 ? stoi(argv[1]) : 1;
                    string size = argc > 2 ? argv[2] : "small";
                    mt19937 rng(seed);
                    int limit = %d;
                    int n = min(5, limit);
                    if (size == "medium") n = min(1000, limit);
                    else if (size == "large") n = min(100000, limit);
                    else if (size == "stress") n = limit;
                    int m = min(n - 1, limit);
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
                """.formatted(maxCount, columns, thirdValue);
    }
}
