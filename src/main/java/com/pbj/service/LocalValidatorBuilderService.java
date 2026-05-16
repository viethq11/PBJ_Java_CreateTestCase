package com.pbj.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

@Service
public class LocalValidatorBuilderService {

    public String buildFromInputSchema(JsonNode inputSchema) {
        if (inputSchema == null || inputSchema.isMissingNode() || inputSchema.isNull()) {
            return fallbackValidator();
        }

        JsonNode lines = inputSchema.path("lines");
        if (!lines.isArray() || lines.isEmpty()) {
            return fallbackValidator();
        }

        List<String> script = new ArrayList<>();
        script.add("import sys");
        script.add("");
        script.add("def die(msg):");
        script.add("    print(msg, file=sys.stderr)");
        script.add("    sys.exit(1)");
        script.add("");
        script.add("def to_int(tok, name):");
        script.add("    try:");
        script.add("        return int(tok)");
        script.add("    except Exception:");
        script.add("        die(f\"{name} is not an integer: {tok}\")");
        script.add("");
        script.add("def eval_len(expr, vars_):");
        script.add("    if isinstance(expr, int):");
        script.add("        return expr");
        script.add("    s = str(expr).strip()");
        script.add("    if s.isdigit():");
        script.add("        return int(s)");
        script.add("    if '-' in s:");
        script.add("        left, right = s.split('-', 1)");
        script.add("        left = left.strip()");
        script.add("        right = right.strip()");
        script.add("        if left not in vars_ or not right.isdigit():");
        script.add("            die(f\"invalid length expression: {s}\")");
        script.add("        return vars_[left] - int(right)");
        script.add("    if s not in vars_:");
        script.add("        die(f\"unknown length reference: {s}\")");
        script.add("    return vars_[s]");
        script.add("");
        script.add("def validate():");
        script.add("    tokens = sys.stdin.read().strip().split()");
        script.add("    idx = 0");
        script.add("");

        List<String> caseScript = new ArrayList<>();
        int lineNo = 0;
        for (JsonNode line : lines) {
            String kind = line.path("kind").asText("").trim().toLowerCase(Locale.ROOT);
            if (kind.isBlank()) {
                lineNo++;
                continue;
            }
            switch (kind) {
                case "scalars" -> appendScalars(caseScript, line, lineNo);
                case "array" -> appendArray(caseScript, line, lineNo);
                case "matrix", "grid" -> appendMatrix(caseScript, line, lineNo);
                case "edges", "queries" -> appendTuples(caseScript, line, lineNo);
                case "string" -> appendString(caseScript, line, lineNo);
                default -> {
                    // raw_lines and unknown kinds: skip strict checking to avoid false negatives
                }
            }
            lineNo++;
        }

        if (inputSchema.path("multiple_test_cases").asBoolean(false)) {
            script.add("    if idx >= len(tokens): die('missing token for T')");
            script.add("    T = to_int(tokens[idx], 'T')");
            script.add("    idx += 1");
            script.add("    if T < 1: die('T below min')");
            script.add("    for _case in range(T):");
            script.add("        vars_ = {}");
            for (String line : caseScript) {
                script.add("    " + line);
            }
        } else {
            script.add("    vars_ = {}");
            script.addAll(caseScript);
        }

        script.add("");
        script.add("    if idx != len(tokens):");
        script.add("        die(f\"unexpected extra tokens: {len(tokens) - idx}\")");
        script.add("");
        script.add("if __name__ == '__main__':");
        script.add("    validate()");
        return String.join("\n", script);
    }

    private void appendScalars(List<String> script, JsonNode line, int lineNo) {
        JsonNode fields = line.path("fields");
        if (!fields.isArray()) return;
        int fieldNo = 0;
        for (JsonNode field : fields) {
            String name = safeName(field.path("name").asText("x_" + lineNo + "_" + fieldNo));
            script.add("    if idx >= len(tokens): die('missing token for " + name + "')");
            script.add("    " + name + " = to_int(tokens[idx], '" + name + "')");
            script.add("    idx += 1");
            appendRangeCheck(script, name, field);
            script.add("    vars_['" + name + "'] = " + name);
            fieldNo++;
        }
    }

    private void appendArray(List<String> script, JsonNode line, int lineNo) {
        String arrName = safeName(line.path("name").asText("arr_" + lineNo));
        String lenExpr = escapePy(line.path("length").isMissingNode() ? "0" : line.path("length").asText("0"));
        script.add("    _len_" + arrName + " = eval_len('" + lenExpr + "', vars_)");
        script.add("    if _len_" + arrName + " < 0: die('negative length for " + arrName + "')");
        script.add("    for _i in range(_len_" + arrName + "):");
        script.add("        if idx >= len(tokens): die('missing token for " + arrName + "[' + str(_i) + ']')");
        script.add("        _v = to_int(tokens[idx], '" + arrName + "[' + str(_i) + ']')");
        script.add("        idx += 1");
        appendRangeCheck(script, "_v", line, 2);
    }

    private void appendMatrix(List<String> script, JsonNode line, int lineNo) {
        String base = "mat_" + lineNo;
        String rowExpr = escapePy(firstPresent(line, "rows", "height", "n").asText("0"));
        String colExpr = escapePy(firstPresent(line, "cols", "columns", "width", "m").asText("0"));
        script.add("    _rows_" + base + " = eval_len('" + rowExpr + "', vars_)");
        script.add("    _cols_" + base + " = eval_len('" + colExpr + "', vars_)");
        script.add("    if _rows_" + base + " < 0 or _cols_" + base + " < 0: die('negative matrix size')");
        script.add("    for _r in range(_rows_" + base + "):");
        script.add("        for _c in range(_cols_" + base + "):");
        script.add("            if idx >= len(tokens): die('missing token for matrix')");
        script.add("            _v = to_int(tokens[idx], 'matrix')");
        script.add("            idx += 1");
        appendRangeCheck(script, "_v", line, 3);
    }

    private void appendTuples(List<String> script, JsonNode line, int lineNo) {
        JsonNode columns = line.path("columns");
        if (!columns.isArray() || columns.isEmpty()) return;
        String tupleName = "tuple_" + lineNo;
        String lenExpr = escapePy(line.path("length").asText("0"));
        script.add("    _len_" + tupleName + " = eval_len('" + lenExpr + "', vars_)");
        script.add("    if _len_" + tupleName + " < 0: die('negative tuple length')");
        script.add("    for _i in range(_len_" + tupleName + "):");
        int col = 0;
        for (JsonNode column : columns) {
            String colName = safeName(column.path("name").asText("col_" + col));
            script.add("        if idx >= len(tokens): die('missing token for " + colName + "')");
            script.add("        _v = to_int(tokens[idx], '" + colName + "')");
            script.add("        idx += 1");
            appendRangeCheck(script, "_v", column, 2);
            col++;
        }
    }

    private void appendString(List<String> script, JsonNode line, int lineNo) {
        String name = safeName(line.path("name").asText("str_" + lineNo));
        script.add("    if idx >= len(tokens): die('missing token for " + name + "')");
        script.add("    _s = tokens[idx]");
        script.add("    idx += 1");
        if (!line.path("length").isMissingNode() && !line.path("length").isNull()) {
            String lenExpr = escapePy(line.path("length").asText("0"));
            script.add("    _len_s = eval_len('" + lenExpr + "', vars_)");
            script.add("    if len(_s) != _len_s: die('invalid length for " + name + "')");
        }
    }

    private void appendRangeCheck(List<String> script, String var, JsonNode node) {
        appendRangeCheck(script, var, node, 1);
    }

    private void appendRangeCheck(List<String> script, String var, JsonNode node, int indentLevel) {
        String indent = "    ".repeat(Math.max(0, indentLevel));
        if (node.path("min").isNumber()) {
            script.add(indent + "if " + var + " < " + node.path("min").asText() + ": die('value below min')");
        }
        if (node.path("max").isNumber()) {
            script.add(indent + "if " + var + " > " + node.path("max").asText() + ": die('value above max')");
        }
    }

    private JsonNode firstPresent(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode candidate = node.path(key);
            if (!candidate.isMissingNode() && !candidate.isNull()) return candidate;
        }
        return node.path("missing");
    }

    private String fallbackValidator() {
        return """
                import sys
                def validate():
                    data = sys.stdin.read()
                    if data is None:
                        sys.exit(1)
                    sys.exit(0)
                if __name__ == '__main__':
                    validate()
                """;
    }

    private String safeName(String raw) {
        if (raw == null || raw.isBlank()) return "v";
        String cleaned = raw.replaceAll("[^A-Za-z0-9_]", "_");
        if (!Character.isLetter(cleaned.charAt(0)) && cleaned.charAt(0) != '_') {
            return "_" + cleaned;
        }
        return cleaned;
    }

    private String escapePy(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("'", "\\'");
    }
}
