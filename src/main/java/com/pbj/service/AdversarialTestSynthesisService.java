package com.pbj.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.pbj.dto.AiResponseDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AdversarialTestSynthesisService {

    private enum Profile {
        EDGE_BOUNDARY,
        OVERFLOW_INT32,
        OVERFLOW_INT64,
        ANTI_GREEDY_SMALL,
        TIE_BREAKING,
        MAX_ASCENDING,
        MAX_DESCENDING,
        ALL_EQUAL,
        ALTERNATING_EXTREMES
    }

    public List<String> synthesize(AiResponseDTO dto) {
        JsonNode schema = dto == null ? null : dto.getInputSchema();
        JsonNode lines = schema == null ? null : schema.path("lines");
        if (lines == null || !lines.isArray() || lines.isEmpty()) return List.of();

        List<String> cases = new ArrayList<>();
        for (Profile profile : requestedProfiles(dto)) {
            String generated = generateCase(lines, profile);
            if (!generated.isBlank()) cases.add(generated);
        }
        return cases;
    }

    private List<Profile> requestedProfiles(AiResponseDTO dto) {
        List<Profile> profiles = new ArrayList<>();

        if (dto != null && dto.getTestProfiles() != null) {
            for (AiResponseDTO.TestProfile profile : dto.getTestProfiles()) {
                Profile mapped = mapProfile(profile == null ? null : profile.getName());
                if (mapped != null && !profiles.contains(mapped)) profiles.add(mapped);
            }
        }

        if (dto != null && dto.getBugClasses() != null) {
            for (AiResponseDTO.BugClass bugClass : dto.getBugClasses()) {
                String name = bugClass == null ? "" : bugClass.getName();
                if (name == null) continue;
                String upper = name.toUpperCase(Locale.ROOT);
                if (upper.contains("OVERFLOW")) {
                    if (!profiles.contains(Profile.OVERFLOW_INT32)) profiles.add(Profile.OVERFLOW_INT32);
                    if (upper.contains("INT64") && !profiles.contains(Profile.OVERFLOW_INT64)) profiles.add(Profile.OVERFLOW_INT64);
                }
                if (upper.contains("GREEDY")) {
                    if (!profiles.contains(Profile.ANTI_GREEDY_SMALL)) profiles.add(Profile.ANTI_GREEDY_SMALL);
                    if (!profiles.contains(Profile.TIE_BREAKING)) profiles.add(Profile.TIE_BREAKING);
                }
            }
        }

        if (profiles.isEmpty()) {
            profiles.add(Profile.EDGE_BOUNDARY);
            profiles.add(Profile.OVERFLOW_INT32);
            profiles.add(Profile.ANTI_GREEDY_SMALL);
            profiles.add(Profile.TIE_BREAKING);
            profiles.add(Profile.MAX_ASCENDING);
            profiles.add(Profile.ALL_EQUAL);
            profiles.add(Profile.ALTERNATING_EXTREMES);
        }
        return profiles;
    }

    private Profile mapProfile(String name) {
        if (name == null || name.isBlank()) return null;
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "edge_boundary" -> Profile.EDGE_BOUNDARY;
            case "overflow_int32" -> Profile.OVERFLOW_INT32;
            case "overflow_int64", "overflow_int64_if_relevant" -> Profile.OVERFLOW_INT64;
            case "anti_greedy_small" -> Profile.ANTI_GREEDY_SMALL;
            case "tie_breaking" -> Profile.TIE_BREAKING;
            case "adversarial_structure", "random_large" -> Profile.MAX_DESCENDING;
            case "random_small" -> Profile.MAX_ASCENDING;
            case "stress_performance" -> Profile.ALTERNATING_EXTREMES;
            default -> null;
        };
    }

    private String generateCase(JsonNode lines, Profile profile) {
        Map<String, Long> vars = chooseScalarValues(lines, profile);
        StringBuilder out = new StringBuilder();

        for (JsonNode line : lines) {
            String kind = line.path("kind").asText("").toLowerCase(Locale.ROOT);
            switch (kind) {
                case "scalars" -> appendScalars(out, line.path("fields"), vars);
                case "array" -> appendArray(out, line, vars, profile);
                case "matrix" -> appendMatrix(out, line, vars, profile);
                case "grid" -> appendGrid(out, line, vars, profile);
                case "string" -> appendString(out, line, vars, profile);
                case "edges", "queries" -> appendTuples(out, line, vars, profile);
                default -> {
                    return "";
                }
            }
        }

        return out.toString();
    }

    private Map<String, Long> chooseScalarValues(JsonNode lines, Profile profile) {
        Map<String, Long> vars = new LinkedHashMap<>();

        for (JsonNode line : lines) {
            if (!"scalars".equalsIgnoreCase(line.path("kind").asText(""))) continue;
            JsonNode fields = line.path("fields");
            if (!fields.isArray()) continue;

            for (JsonNode field : fields) {
                String name = field.path("name").asText("");
                if (name.isBlank()) continue;
                long lo = numericBound(field.path("min"), 1L);
                long hi = numericBound(field.path("max"), defaultScalarMax(name));
                if (hi < lo) hi = lo;

                long value = countLike(name)
                        ? countValueForProfile(profile, lo, hi)
                        : valueForProfile(profile, lo, hi, 0, true);
                putVar(vars, name, value);
            }
        }

        constrainTupleLengths(lines, vars);
        constrainGridAreas(lines, vars);
        return vars;
    }

    private void constrainTupleLengths(JsonNode lines, Map<String, Long> vars) {
        long nodeCount = Math.max(1L, getVar(vars, "N", getVar(vars, "n", 200_000L)));
        for (JsonNode line : lines) {
            String kind = line.path("kind").asText("").toLowerCase(Locale.ROOT);
            if (!"edges".equals(kind)) continue;
            String lengthRef = line.path("length").asText("");
            if (!isIdentifier(lengthRef)) continue;
            if (!line.path("multi_edge_allowed").asBoolean(true) && countNodeColumns(line.path("columns")) >= 2) {
                putVar(vars, lengthRef, Math.max(0L, Math.min(getVar(vars, lengthRef, nodeCount - 1), nodeCount - 1)));
            }
        }
    }

    private void constrainGridAreas(JsonNode lines, Map<String, Long> vars) {
        for (JsonNode line : lines) {
            String kind = line.path("kind").asText("").toLowerCase(Locale.ROOT);
            if (!"matrix".equals(kind) && !"grid".equals(kind)) continue;

            String rowRef = firstPresent(line, "rows", "height", "n").asText("");
            String colRef = firstPresent(line, "cols", "columns", "width", "m").asText("");
            if (!isIdentifier(rowRef) || !isIdentifier(colRef)) continue;

            long rows = getVar(vars, rowRef, 1000L);
            long cols = getVar(vars, colRef, 1000L);
            long maxArea = 1_000_000L;
            if (rows * cols <= maxArea) continue;

            long side = Math.max(1L, (long) Math.sqrt(maxArea));
            putVar(vars, rowRef, Math.min(rows, side));
            putVar(vars, colRef, Math.min(cols, Math.max(1L, maxArea / getVar(vars, rowRef, side))));
        }
    }

    private void appendScalars(StringBuilder out, JsonNode fields, Map<String, Long> vars) {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) out.append(' ');
            String name = fields.get(i).path("name").asText("");
            out.append(getVar(vars, name, 1L));
        }
        out.append('\n');
    }

    private void appendArray(StringBuilder out, JsonNode line, Map<String, Long> vars, Profile profile) {
        long len = clamp(lengthValue(line.path("length"), vars, 200_000L), 0L, 200_000L);
        long lo = numericBound(line.path("min"), 1L);
        long hi = numericBound(line.path("max"), 1_000_000_000L);
        if (hi < lo) hi = lo;

        if (profile == Profile.ANTI_GREEDY_SMALL || profile == Profile.TIE_BREAKING) {
            len = Math.max(3L, Math.min(len, 8L));
        }

        for (long i = 0; i < len; i++) {
            if (i > 0) out.append(' ');
            out.append(valueForProfile(profile, lo, hi, i, false));
        }
        out.append('\n');
    }

    private void appendMatrix(StringBuilder out, JsonNode line, Map<String, Long> vars, Profile profile) {
        long rows = clamp(lengthValue(firstPresent(line, "rows", "height", "n"), vars, 1000L), 1L, 2000L);
        long cols = clamp(lengthValue(firstPresent(line, "cols", "columns", "width", "m"), vars, 1000L), 1L, 2000L);
        long lo = numericBound(line.path("min"), 0L);
        long hi = numericBound(line.path("max"), 1_000_000_000L);
        if (hi < lo) hi = lo;

        if (profile == Profile.ANTI_GREEDY_SMALL || profile == Profile.TIE_BREAKING) {
            rows = Math.min(rows, 5L);
            cols = Math.min(cols, 5L);
        }

        for (long r = 0; r < rows; r++) {
            for (long c = 0; c < cols; c++) {
                if (c > 0) out.append(' ');
                out.append(valueForProfile(profile, lo, hi, r * cols + c, false));
            }
            out.append('\n');
        }
    }

    private void appendGrid(StringBuilder out, JsonNode line, Map<String, Long> vars, Profile profile) {
        long rows = clamp(lengthValue(firstPresent(line, "rows", "height", "n"), vars, 1000L), 1L, 2000L);
        long cols = clamp(lengthValue(firstPresent(line, "cols", "columns", "width", "m"), vars, 1000L), 1L, 2000L);
        String alphabet = alphabet(line);

        if (profile == Profile.ANTI_GREEDY_SMALL || profile == Profile.TIE_BREAKING) {
            rows = Math.min(rows, 6L);
            cols = Math.min(cols, 6L);
        }

        for (long r = 0; r < rows; r++) {
            for (long c = 0; c < cols; c++) {
                out.append(charForProfile(alphabet, profile, r * cols + c));
            }
            out.append('\n');
        }
    }

    private void appendString(StringBuilder out, JsonNode line, Map<String, Long> vars, Profile profile) {
        long len = clamp(lengthValue(line.path("length"), vars, 200_000L), 1L, 200_000L);
        String alphabet = alphabet(line);
        if (profile == Profile.ANTI_GREEDY_SMALL || profile == Profile.TIE_BREAKING) {
            len = Math.min(len, 12L);
        }
        for (long i = 0; i < len; i++) {
            out.append(charForProfile(alphabet, profile, i));
        }
        out.append('\n');
    }

    private void appendTuples(StringBuilder out, JsonNode line, Map<String, Long> vars, Profile profile) {
        JsonNode columns = line.path("columns");
        if (!columns.isArray() || columns.isEmpty()) return;

        long cnt = clamp(lengthValue(line.path("length"), vars, 200_000L), 0L, 200_000L);
        long nodeMax = Math.max(1L, getVar(vars, "N", getVar(vars, "n", 200_000L)));
        boolean edgeLike = "edges".equalsIgnoreCase(line.path("kind").asText("")) && countNodeColumns(columns) >= 2;
        if (edgeLike && !line.path("multi_edge_allowed").asBoolean(true)) {
            cnt = Math.min(cnt, Math.max(0L, nodeMax - 1));
        }
        if (profile == Profile.ANTI_GREEDY_SMALL || profile == Profile.TIE_BREAKING) {
            cnt = Math.max(2L, Math.min(cnt, 6L));
        }

        for (long i = 0; i < cnt; i++) {
            for (int c = 0; c < columns.size(); c++) {
                if (c > 0) out.append(' ');
                JsonNode column = columns.get(c);
                String type = column.path("type").asText("int").toLowerCase(Locale.ROOT);
                if ("node".equals(type) || "vertex".equals(type)) {
                    out.append(nodeValue(profile, i, c, nodeMax));
                } else {
                    long lo = numericBound(column.path("min"), 1L);
                    long hi = numericBound(column.path("max"), 1_000_000_000L);
                    if (hi < lo) hi = lo;
                    out.append(valueForProfile(profile, lo, hi, i + c, false));
                }
            }
            out.append('\n');
        }
    }

    private long nodeValue(Profile profile, long i, int column, long nodeMax) {
        if (nodeMax <= 1) return 1L;
        return switch (profile) {
            case EDGE_BOUNDARY -> column == 0 ? 1L : nodeMax;
            case OVERFLOW_INT32, OVERFLOW_INT64 -> column == 0 ? Math.max(1L, nodeMax - 1) : nodeMax;
            case ANTI_GREEDY_SMALL -> column == 0 ? Math.min(nodeMax, i + 1) : Math.min(nodeMax, ((i + 2) % nodeMax) + 1);
            case TIE_BREAKING -> column == 0 ? ((i % 2 == 0) ? 1L : Math.min(nodeMax, 2L)) : nodeMax;
            case MAX_DESCENDING -> column == 0 ? Math.max(1L, nodeMax - i % nodeMax) : Math.max(1L, nodeMax - ((i + 1) % nodeMax));
            case ALL_EQUAL -> column == 0 ? 1L : Math.min(2L, nodeMax);
            case ALTERNATING_EXTREMES -> column == 0 ? (i % 2 == 0 ? 1L : nodeMax) : (i % 2 == 0 ? nodeMax : 1L);
            case MAX_ASCENDING -> column == 0 ? (i % nodeMax) + 1L : ((i + 1) % nodeMax) + 1L;
        };
    }

    private long valueForProfile(Profile profile, long lo, long hi, long index, boolean scalarContext) {
        long mid = lo + Math.max(0L, (hi - lo) / 2L);
        return switch (profile) {
            case EDGE_BOUNDARY -> index % 2 == 0 ? lo : hi;
            case OVERFLOW_INT32 -> overflowValue(lo, hi, index, 2_147_483_647L, scalarContext);
            case OVERFLOW_INT64 -> overflowValue(lo, hi, index, Long.MAX_VALUE, scalarContext);
            case ANTI_GREEDY_SMALL -> antiGreedyValue(lo, hi, index);
            case TIE_BREAKING -> index % 3 == 0 ? mid : (index % 2 == 0 ? lo : mid);
            case MAX_ASCENDING -> lo + Math.min(hi - lo, index);
            case MAX_DESCENDING -> hi - Math.min(hi - lo, index);
            case ALL_EQUAL -> mid;
            case ALTERNATING_EXTREMES -> index % 2 == 0 ? lo : hi;
        };
    }

    private long countValueForProfile(Profile profile, long lo, long hi) {
        long capped = Math.min(hi, 200_000L);
        return switch (profile) {
            case ANTI_GREEDY_SMALL, TIE_BREAKING -> Math.max(lo, Math.min(capped, 6L));
            case ALL_EQUAL -> Math.max(lo, Math.min(capped, 50_000L));
            default -> Math.max(lo, capped);
        };
    }

    private char charForProfile(String alphabet, Profile profile, long index) {
        if (alphabet.isBlank()) alphabet = "01";
        int last = alphabet.length() - 1;
        int selected = switch (profile) {
            case EDGE_BOUNDARY -> index % 2 == 0 ? 0 : last;
            case OVERFLOW_INT32, OVERFLOW_INT64 -> last;
            case ANTI_GREEDY_SMALL -> (int) ((index + 1) % alphabet.length());
            case TIE_BREAKING -> index % 3 == 0 ? 0 : Math.min(1, last);
            case MAX_ASCENDING -> (int) (index % alphabet.length());
            case MAX_DESCENDING -> last - (int) (index % alphabet.length());
            case ALL_EQUAL -> 0;
            case ALTERNATING_EXTREMES -> index % 2 == 0 ? 0 : last;
        };
        return alphabet.charAt(Math.max(0, Math.min(last, selected)));
    }

    private long antiGreedyValue(long lo, long hi, long index) {
        if (hi <= lo) return lo;
        long span = Math.max(1L, hi - lo);
        return switch ((int) (index % 5)) {
            case 0 -> lo + Math.min(span, Math.max(1L, span / 3L));
            case 1 -> hi;
            case 2 -> lo;
            case 3 -> lo + Math.min(span, Math.max(2L, span / 2L));
            default -> lo + Math.min(span, Math.max(3L, (span * 2L) / 3L));
        };
    }

    private long overflowValue(long lo, long hi, long index, long threshold, boolean scalarContext) {
        if (hi <= lo) return hi;
        if (hi >= threshold / 4L) {
            long nearHi = hi - Math.min(2L, Math.max(0L, hi - lo));
            return index % 2 == 0 ? hi : nearHi;
        }
        if (scalarContext) return hi;
        return index % 2 == 0 ? hi : Math.max(lo, hi - Math.min(3L, hi - lo));
    }

    private long lengthValue(JsonNode node, Map<String, Long> vars, long fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) return fallback;
        if (node.isNumber()) return node.asLong();
        String text = node.asText("").trim();
        if (text.matches("\\d+")) return Long.parseLong(text);
        if (isIdentifier(text)) return getVar(vars, text, fallback);
        if (text.matches("[A-Za-z_][A-Za-z0-9_]*\\s*-\\s*\\d+")) {
            String[] parts = text.split("-");
            return Math.max(0L, getVar(vars, parts[0].trim(), fallback) - Long.parseLong(parts[1].trim()));
        }
        return fallback;
    }

    private String alphabet(JsonNode line) {
        String alphabet = firstText(line, "alphabet", "chars", "symbols", "domain");
        String normalized = alphabet.toLowerCase(Locale.ROOT).replace(" ", "");
        if (alphabet.isBlank()) return "01";
        if (normalized.contains("0") && normalized.contains("1") && !normalized.matches(".*[2-9].*")) return "01";
        if (alphabet.contains("#") || alphabet.contains(".")) return ".#";
        if (normalized.contains("lowercase") || normalized.contains("letter")) return "abcdefghijklmnopqrstuvwxyz";
        alphabet = alphabet.replace("\\", "").replace("\"", "");
        return alphabet.isBlank() ? "01" : alphabet;
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

    private long defaultScalarMax(String name) {
        return countLike(name) ? 200_000L : 1_000_000_000L;
    }

    private boolean countLike(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.matches("[nmkqtr]") || lower.contains("count") || lower.contains("size")
                || lower.contains("len") || lower.contains("length");
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

    private void putVar(Map<String, Long> vars, String name, long value) {
        vars.put(name, value);
        vars.put(name.toLowerCase(Locale.ROOT), value);
    }

    private long getVar(Map<String, Long> vars, String name, long fallback) {
        Long value = vars.get(name);
        if (value != null) return value;
        value = vars.get(name.toLowerCase(Locale.ROOT));
        return value == null ? fallback : value;
    }

    private boolean isIdentifier(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private long clamp(long value, long lo, long hi) {
        if (hi < lo) hi = lo;
        return Math.max(lo, Math.min(value, hi));
    }
}
