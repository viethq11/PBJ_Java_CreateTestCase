package com.pbj.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pbj.dto.AiResponseDTO;
import com.pbj.dto.SemanticSpecDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SemanticArtifactCompilerService {
    private static final Pattern RANGE_PATTERN = Pattern.compile("(-?\\d+(?:\\^\\d+)?)\\s*<=\\s*[A-Za-z_][A-Za-z0-9_]*\\s*<=\\s*(-?\\d+(?:\\^\\d+)?)");

    private final ObjectMapper objectMapper;

    public void compileMissingArtifacts(AiResponseDTO dto) {
        if (dto == null) return;

        if (dto.getSemanticSpec() != null && dto.getSemanticSpec().getInputModel() != null) {
            JsonNode model = dto.getSemanticSpec().getInputModel();
            if (isCommandModel(model)) {
                if (isMissing(dto.getInputSchema())) {
                    dto.setInputSchema(buildCommandInputSchema(dto.getSemanticSpec(), model));
                }
                if (dto.getTestPlan() == null) {
                    dto.setTestPlan(buildCommandTestPlan(model));
                }
                if (isBlank(dto.getGeneratorCode())) {
                    dto.setGeneratorCode(buildCommandGenerator(dto.getSemanticSpec(), model));
                    dto.setGeneratorLanguage("cpp");
                }
            } else if (isArrayModel(model)) {
                if (isMissing(dto.getInputSchema())) {
                    dto.setInputSchema(buildArrayInputSchema(dto.getSemanticSpec(), model));
                }
                if (dto.getTestPlan() == null) {
                    dto.setTestPlan(buildArrayTestPlan());
                }
                if (isBlank(dto.getGeneratorCode())) {
                    dto.setGeneratorCode(buildArrayGenerator(dto.getSemanticSpec(), model));
                    dto.setGeneratorLanguage("cpp");
                }
            } else if (isScalarModel(model)) {
                if (isMissing(dto.getInputSchema())) {
                    dto.setInputSchema(buildScalarInputSchema(dto.getSemanticSpec(), model));
                }
                if (dto.getTestPlan() == null) {
                    dto.setTestPlan(buildScalarTestPlan());
                }
                if (isBlank(dto.getGeneratorCode())) {
                    dto.setGeneratorCode(buildScalarGenerator(dto.getSemanticSpec(), model));
                    dto.setGeneratorLanguage("cpp");
                }
            }
        }

        ensureFallbackArtifacts(dto);
    }

    private boolean isMissing(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isCommandModel(JsonNode model) {
        for (JsonNode block : structuredSections(model)) {
            if (block.path("variants").isArray()
                    && !block.path("variants").isEmpty()
                    && block.path("variants").get(0).has("keyword")) {
                return true;
            }
            if (loopCommandVariants(block).isArray() && !loopCommandVariants(block).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean isArrayModel(JsonNode model) {
        JsonNode variant = firstArrayVariant(model);
        return variant != null && !firstHeaderNames(model).isEmpty();
    }

    private boolean isScalarModel(JsonNode model) {
        return firstArrayVariant(model) == null && !firstHeaderNames(model).isEmpty();
    }

    private JsonNode buildCommandInputSchema(SemanticSpecDTO spec, JsonNode model) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("multiple_test_cases", model.path("type").asText("").toLowerCase(Locale.ROOT).contains("multi"));
        ArrayNode lines = schema.putArray("lines");

        JsonNode header = firstHeader(model);
        if (header != null && header.isArray() && !header.isEmpty()) {
            ObjectNode scalarLine = lines.addObject();
            scalarLine.put("kind", "scalars");
            ArrayNode fields = scalarLine.putArray("fields");
            for (JsonNode fieldNode : header) {
                String name = fieldNode.asText("");
                if (name.isBlank()) continue;
                Bounds bounds = boundsFor(spec, name, defaultMin(name), defaultMax(name));
                ObjectNode field = fields.addObject();
                field.put("name", name);
                field.put("type", "int");
                field.put("min", bounds.min());
                field.put("max", bounds.max());
            }
        }

        String repeat = firstRepeat(model);
        if (!repeat.isBlank()) {
            ObjectNode raw = lines.addObject();
            raw.put("kind", "raw_lines");
            raw.put("length", repeat);
        }
        return schema;
    }

    private AiResponseDTO.TestPlan buildCommandTestPlan(JsonNode model) {
        AiResponseDTO.TestPlan plan = new AiResponseDTO.TestPlan();
        plan.setProblemType("generic_command_stream");
        plan.setIntendedSolution("Process each command in order while maintaining the problem state and answer each query.");

        AiResponseDTO.TestFamily mixed = new AiResponseDTO.TestFamily();
        mixed.setName("mixed_commands");
        mixed.setDifficulty("small");
        mixed.setTarget(List.of("state transitions", "query after update"));
        mixed.setConstraints("small command count with multiple command variants");
        mixed.setExpected("valid mixed command stream");
        mixed.setReason("checks parser and state updates");

        AiResponseDTO.TestFamily stress = new AiResponseDTO.TestFamily();
        stress.setName("stress_commands");
        stress.setDifficulty("stress");
        stress.setTarget(List.of("performance", "large command count"));
        stress.setConstraints("command count near maximum");
        stress.setExpected("valid large command stream");
        stress.setReason("checks asymptotic behavior");
        plan.setTestFamilies(List.of(mixed, stress));
        return plan;
    }

    private JsonNode buildArrayInputSchema(SemanticSpecDTO spec, JsonNode model) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("multiple_test_cases", isMultiTest(model));
        ArrayNode lines = schema.putArray("lines");

        addScalarHeaderLine(lines, spec, firstHeaderNames(model));

        JsonNode variant = firstArrayVariant(model);
        String arrayName = variant.path("name").asText("a");
        String length = variant.path("length").asText(firstHeaderNames(model).get(0));
        Bounds bounds = boundsFor(spec, arrayName + "_i", -1_000_000_000L, 1_000_000_000L);
        ObjectNode array = lines.addObject();
        array.put("kind", "array");
        array.put("name", arrayName);
        array.put("length", length);
        array.put("min", bounds.min());
        array.put("max", bounds.max());
        return schema;
    }

    private JsonNode buildScalarInputSchema(SemanticSpecDTO spec, JsonNode model) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("multiple_test_cases", isMultiTest(model));
        ArrayNode lines = schema.putArray("lines");
        addScalarHeaderLine(lines, spec, firstHeaderNames(model));
        return schema;
    }

    private void addScalarHeaderLine(ArrayNode lines, SemanticSpecDTO spec, List<String> headerNames) {
        if (headerNames.isEmpty()) return;
        ObjectNode scalarLine = lines.addObject();
        scalarLine.put("kind", "scalars");
        ArrayNode fields = scalarLine.putArray("fields");
        for (String name : headerNames) {
            Bounds bounds = boundsFor(spec, name, defaultMin(name), defaultMax(name));
            ObjectNode field = fields.addObject();
            field.put("name", name);
            field.put("type", "int");
            field.put("min", bounds.min());
            field.put("max", bounds.max());
        }
    }

    private AiResponseDTO.TestPlan buildArrayTestPlan() {
        return genericStructuredPlan("generic_array_input", "Generate boundary, random-small, and large array cases.");
    }

    private AiResponseDTO.TestPlan buildScalarTestPlan() {
        return genericStructuredPlan("generic_scalar_input", "Generate boundary and representative scalar combinations.");
    }

    private AiResponseDTO.TestPlan genericStructuredPlan(String type, String intendedSolution) {
        AiResponseDTO.TestPlan plan = new AiResponseDTO.TestPlan();
        plan.setProblemType(type);
        plan.setIntendedSolution(intendedSolution);

        AiResponseDTO.TestFamily boundary = new AiResponseDTO.TestFamily();
        boundary.setName("boundary");
        boundary.setDifficulty("small");
        boundary.setTarget(List.of("minimum values", "maximum values"));
        boundary.setConstraints("values near declared bounds");
        boundary.setExpected("valid structured input");
        boundary.setReason("checks parser and off-by-one handling");

        AiResponseDTO.TestFamily stress = new AiResponseDTO.TestFamily();
        stress.setName("stress");
        stress.setDifficulty("stress");
        stress.setTarget(List.of("performance"));
        stress.setConstraints("sizes near declared upper bounds");
        stress.setExpected("valid structured input");
        stress.setReason("checks asymptotic behavior");
        plan.setTestFamilies(List.of(boundary, stress));
        return plan;
    }

    private String buildCommandGenerator(SemanticSpecDTO spec, JsonNode model) {
        JsonNode header = firstHeader(model);
        String repeat = firstRepeat(model);
        JsonNode variants = firstVariants(model);
        List<String> headerNames = new ArrayList<>();
        if (header != null && header.isArray()) {
            header.forEach(node -> headerNames.add(node.asText("")));
        }

        String nName = headerNames.isEmpty() ? "n" : headerNames.get(0);
        String repeatName = repeat.isBlank() ? (headerNames.size() > 1 ? headerNames.get(1) : "m") : repeat;
        Bounds nBounds = boundsFor(spec, nName, 1, 100);
        Bounds repeatBounds = boundsFor(spec, repeatName, 1, 100000);

        StringBuilder commandCases = new StringBuilder();
        for (int i = 0; i < variants.size(); i++) {
            JsonNode variant = variants.get(i);
            String keyword = variant.path("keyword").asText("CMD" + i);
            List<String> args = new ArrayList<>();
            if (variant.path("args").isArray()) {
                variant.path("args").forEach(arg -> args.add(arg.asText("")));
            }
            if (i > 0) commandCases.append("        else ");
            else commandCases.append("        ");
            commandCases.append("if (pick == ").append(i).append(") {\n");
            commandCases.append("            cout << \"").append(keyword).append("\";\n");
            for (String arg : args) {
                Bounds bounds = argumentBounds(spec, arg, nBounds);
                commandCases.append("            cout << ' ' << chooseValue(rng, ")
                        .append(bounds.min()).append("LL, ").append(bounds.max()).append("LL);\n");
            }
            commandCases.append("        }\n");
        }

        return """
                #include <bits/stdc++.h>
                using namespace std;

                long long clampValue(long long value, long long lo, long long hi) {
                    if (hi < lo) hi = lo;
                    return max(lo, min(value, hi));
                }

                long long chooseCount(const string& profile, long long lo, long long hi) {
                    long long target = 5;
                    if (profile == "medium") target = 1000;
                    else if (profile == "random_large") target = 20000;
                    else if (profile == "stress_performance") target = hi;
                    return clampValue(target, lo, hi);
                }

                long long chooseValue(mt19937& rng, long long lo, long long hi) {
                    if (hi < lo) hi = lo;
                    unsigned long long span = (unsigned long long)(hi - lo + 1);
                    return lo + (long long)(rng() %% span);
                }

                int main(int argc, char** argv) {
                    int seed = argc > 1 ? stoi(argv[1]) : 1;
                    string profile = argc > 2 ? argv[2] : "random_small";
                    mt19937 rng(seed);
                    int T = profile == "stress_performance" ? 1 : 2;
                    cout << T << "\\n";
                    while (T--) {
                        long long %s = chooseCount(profile, %dLL, %dLL);
                        long long %s = chooseCount(profile, %dLL, %dLL);
                        cout << %s << ' ' << %s << "\\n";
                        for (long long i = 0; i < %s; i++) {
                            int pick = (int)(rng() %% %d);
                %s
                            cout << "\\n";
                        }
                    }
                    return 0;
                }
                """.formatted(
                nName, nBounds.min(), nBounds.max(),
                repeatName, repeatBounds.min(), repeatBounds.max(),
                nName, repeatName, repeatName, Math.max(1, variants.size()), commandCases);
    }

    private String buildArrayGenerator(SemanticSpecDTO spec, JsonNode model) {
        List<String> headerNames = firstHeaderNames(model);
        String nName = headerNames.get(0);
        Bounds nBounds = boundsFor(spec, nName, 1, 100);
        JsonNode variant = firstArrayVariant(model);
        String arrayName = variant.path("name").asText("a");
        Bounds valueBounds = boundsFor(spec, arrayName + "_i", -1_000_000_000L, 1_000_000_000L);
        boolean distinct = hasDistinctConstraint(spec, arrayName);

        String emitValue = distinct
                ? """
                            unordered_set<long long> used;
                            while ((long long)used.size() < %s) {
                                used.insert(chooseValue(rng, %dLL, %dLL));
                            }
                            vector<long long> values(used.begin(), used.end());
                            shuffle(values.begin(), values.end(), rng);
                            for (long long i = 0; i < %s; i++) {
                                if (i) cout << ' ';
                                cout << values[(size_t)i];
                            }
                        """.formatted(nName, valueBounds.min(), valueBounds.max(), nName)
                : """
                            for (long long i = 0; i < %s; i++) {
                                if (i) cout << ' ';
                                cout << chooseValue(rng, %dLL, %dLL);
                            }
                        """.formatted(nName, valueBounds.min(), valueBounds.max());

        return """
                #include <bits/stdc++.h>
                using namespace std;

                long long clampValue(long long value, long long lo, long long hi) {
                    if (hi < lo) hi = lo;
                    return max(lo, min(value, hi));
                }

                long long chooseCount(const string& profile, long long lo, long long hi) {
                    long long target = 5;
                    if (profile == "medium") target = 1000;
                    else if (profile == "random_large") target = min(hi, 20000LL);
                    else if (profile == "stress_performance") target = hi;
                    return clampValue(target, lo, hi);
                }

                long long chooseValue(mt19937& rng, long long lo, long long hi) {
                    if (hi < lo) hi = lo;
                    unsigned long long span = (unsigned long long)(hi - lo + 1);
                    return lo + (long long)(rng() %% span);
                }

                int main(int argc, char** argv) {
                    int seed = argc > 1 ? stoi(argv[1]) : 1;
                    string profile = argc > 2 ? argv[2] : "random_small";
                    mt19937 rng(seed);
                    int T = %s;
                    if (%s) cout << T << "\\n";
                    while (T--) {
                        long long %s = chooseCount(profile, %dLL, %dLL);
                        cout << %s << "\\n";
                %s
                        cout << "\\n";
                    }
                    return 0;
                }
                """.formatted(
                isMultiTest(model) ? "profile == \"stress_performance\" ? 1 : 2" : "1",
                isMultiTest(model),
                nName, nBounds.min(), nBounds.max(), nName, emitValue);
    }

    private String buildScalarGenerator(SemanticSpecDTO spec, JsonNode model) {
        List<String> headerNames = firstHeaderNames(model);
        StringBuilder declarations = new StringBuilder();
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < headerNames.size(); i++) {
            String name = headerNames.get(i);
            Bounds bounds = boundsFor(spec, name, defaultMin(name), defaultMax(name));
            declarations.append("        long long ").append(name)
                    .append(" = chooseScalar(profile, ").append(bounds.min()).append("LL, ")
                    .append(bounds.max()).append("LL, ").append(i).append(");\n");
            if (i > 0) output.append(" << ' ' ");
            output.append("<< ").append(name);
        }

        return """
                #include <bits/stdc++.h>
                using namespace std;

                long long chooseScalar(const string& profile, long long lo, long long hi, int offset) {
                    if (hi < lo) hi = lo;
                    if (profile == "stress_performance") return hi;
                    if (profile == "edge_boundary") return offset %% 2 == 0 ? lo : hi;
                    long long span = hi - lo + 1;
                    return lo + min(span - 1, (long long)(offset + 3));
                }

                int main(int argc, char** argv) {
                    string profile = argc > 2 ? argv[2] : "random_small";
                %s
                    cout %s << "\\n";
                    return 0;
                }
                """.formatted(declarations, output);
    }

    private Bounds argumentBounds(SemanticSpecDTO spec, String arg, Bounds nBounds) {
        if (arg == null || arg.isBlank()) return new Bounds(-1_000_000_000L, 1_000_000_000L);
        String lower = arg.toLowerCase(Locale.ROOT);
        if (lower.matches("[xyz](\\d+)?")) {
            return new Bounds(1L, nBounds.max());
        }
        return boundsFor(spec, arg, -1_000_000_000L, 1_000_000_000L);
    }

    private JsonNode firstHeader(JsonNode model) {
        for (JsonNode block : structuredSections(model)) {
            if (block.path("header").isArray() && !block.path("header").isEmpty()) return block.path("header");
            if (block.path("read_variables").isArray() && !block.path("read_variables").isEmpty()) {
                return block.path("read_variables");
            }
        }
        return null;
    }

    private List<String> firstHeaderNames(JsonNode model) {
        List<String> names = new ArrayList<>();
        JsonNode header = firstHeader(model);
        if (header != null && header.isArray()) {
            header.forEach(node -> {
                String name = node.asText("");
                if (!name.isBlank()) names.add(name);
            });
        }
        return names;
    }

    private JsonNode firstArrayVariant(JsonNode model) {
        for (JsonNode block : structuredSections(model)) {
            if (!block.path("variants").isArray()) continue;
            for (JsonNode variant : block.path("variants")) {
                if ("array".equalsIgnoreCase(variant.path("type").asText(""))) {
                    return variant;
                }
            }
        }
        for (JsonNode section : model.path("sections")) {
            if (!section.path("data").isArray()
                    || !section.path("data_type").asText("").toLowerCase(Locale.ROOT).contains("array")) {
                continue;
            }
            ObjectNode synthetic = objectMapper.createObjectNode();
            synthetic.put("type", "array");
            synthetic.put("name", section.path("data").get(0).asText("a"));
            JsonNode header = section.path("header");
            synthetic.put("length", header.isArray() && !header.isEmpty() ? header.get(0).asText("N") : "N");
            synthetic.put("value_type", "integer");
            return synthetic;
        }
        return null;
    }

    private Iterable<JsonNode> structuredSections(JsonNode model) {
        if (model == null) return List.of();
        if (model.path("blocks").isArray() && !model.path("blocks").isEmpty()) {
            return model.path("blocks");
        }
        if (model.path("sections").isArray()) {
            List<JsonNode> sections = new ArrayList<>();
            for (JsonNode section : model.path("sections")) {
                if (section.isArray()) {
                    ObjectNode synthetic = objectMapper.createObjectNode();
                    synthetic.set("header", section.deepCopy());
                    sections.add(synthetic);
                } else {
                    sections.add(section);
                }
            }
            return sections;
        }
        return List.of();
    }

    private boolean isMultiTest(JsonNode model) {
        return model.path("type").asText("").toLowerCase(Locale.ROOT).contains("multi")
                || !model.path("test_count").asText("").isBlank();
    }

    private boolean hasDistinctConstraint(SemanticSpecDTO spec, String arrayName) {
        if (spec == null || spec.getConstraints() == null) return false;
        String lowerArray = arrayName == null ? "" : arrayName.toLowerCase(Locale.ROOT);
        return spec.getConstraints().fields().hasNext() && containsDistinctConstraint(spec.getConstraints(), lowerArray);
    }

    private boolean containsDistinctConstraint(JsonNode node, String lowerArray) {
        if (node == null || node.isMissingNode() || node.isNull()) return false;
        if (node.isTextual()) {
            String text = node.asText("").toLowerCase(Locale.ROOT);
            return text.contains("distinct") && (lowerArray.isBlank() || text.contains(lowerArray));
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                if (key.contains("distinct") && (lowerArray.isBlank() || key.contains(lowerArray))) return true;
                if (containsDistinctConstraint(entry.getValue(), lowerArray)) return true;
            }
        }
        return false;
    }

    private String firstRepeat(JsonNode model) {
        for (JsonNode block : structuredSections(model)) {
            if (!block.path("repeat").asText("").isBlank()) return block.path("repeat").asText("");
            JsonNode loop = block.path("loop");
            if (!loop.path("count_variable").asText("").isBlank()) {
                return loop.path("count_variable").asText("");
            }
        }
        return "";
    }

    private JsonNode firstVariants(JsonNode model) {
        for (JsonNode block : structuredSections(model)) {
            if (block.path("variants").isArray() && !block.path("variants").isEmpty()) return block.path("variants");
            JsonNode nested = loopCommandVariants(block);
            if (nested.isArray() && !nested.isEmpty()) return nested;
        }
        return objectMapper.createArrayNode();
    }

    private JsonNode loopCommandVariants(JsonNode block) {
        JsonNode body = block.path("loop").path("body");
        if (!body.isArray()) return objectMapper.missingNode();
        for (JsonNode item : body) {
            if (item.path("command_variants").isArray() && !item.path("command_variants").isEmpty()) {
                return item.path("command_variants");
            }
        }
        return objectMapper.missingNode();
    }

    private Bounds boundsFor(SemanticSpecDTO spec, String name, long fallbackMin, long fallbackMax) {
        if (spec == null || spec.getConstraints() == null || name == null || name.isBlank()) {
            return new Bounds(fallbackMin, fallbackMax);
        }
        String raw = spec.getConstraints().path(name).asText("");
        Matcher matcher = RANGE_PATTERN.matcher(raw.replace(" ", ""));
        if (matcher.find()) {
            return new Bounds(parseBound(matcher.group(1), fallbackMin), parseBound(matcher.group(2), fallbackMax));
        }
        return new Bounds(fallbackMin, fallbackMax);
    }

    private long defaultMin(String name) {
        return name != null && name.equalsIgnoreCase("T") ? 1L : 1L;
    }

    private long defaultMax(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.equals("n")) return 100L;
        if (lower.equals("m") || lower.equals("q")) return 100_000L;
        return 100_000L;
    }

    private long parseBound(String text, long fallback) {
        if (text == null || text.isBlank()) return fallback;
        if (text.matches("-?\\d+")) return Long.parseLong(text);
        boolean negative = text.startsWith("-");
        String normalized = negative ? text.substring(1) : text;
        if (normalized.matches("10\\^\\d+")) {
            int power = Integer.parseInt(normalized.substring(3));
            long value = 1L;
            for (int i = 0; i < power && value < 1_000_000_000L; i++) value *= 10L;
            return negative ? -value : value;
        }
        return fallback;
    }

    private void ensureFallbackArtifacts(AiResponseDTO dto) {
        if (isMissing(dto.getInputSchema())) {
            ObjectNode schema = objectMapper.createObjectNode();
            boolean multi = false;
            if (dto.getSemanticSpec() != null) {
                SemanticSpecDTO spec = dto.getSemanticSpec();
                if (spec.getInputModel() != null) {
                    multi = isMultiTest(spec.getInputModel());
                }
                if (!multi && spec.getGraphType() != null) {
                    multi = spec.getGraphType().toLowerCase(Locale.ROOT).contains("multi");
                }
            }
            if (!multi && dto.getInputFormat() != null) {
                String inputLower = dto.getInputFormat().toLowerCase(Locale.ROOT);
                multi = inputLower.contains("multiple test") || inputLower.contains("test cases");
            }
            
            schema.put("multiple_test_cases", multi);
            ArrayNode lines = schema.putArray("lines");
            
            ObjectNode scalarLine = lines.addObject();
            scalarLine.put("kind", "scalars");
            ArrayNode fields = scalarLine.putArray("fields");
            
            List<String> vars = new ArrayList<>();
            if (dto.getSemanticSpec() != null && dto.getSemanticSpec().getQueryVariables() != null) {
                for (String q : dto.getSemanticSpec().getQueryVariables()) {
                    if (q != null && !q.isBlank()) vars.add(q);
                }
            }
            if (vars.isEmpty()) {
                String text = (dto.getInputFormat() != null ? dto.getInputFormat() : "") 
                            + " " + (dto.getConstraints() != null ? dto.getConstraints() : "");
                String textLower = text.toLowerCase(Locale.ROOT);
                if (textLower.contains(" r ") || textLower.contains(" r,") || textLower.contains(" r\n")) vars.add("R");
                if (textLower.contains(" c ") || textLower.contains(" c,") || textLower.contains(" c\n")) vars.add("C");
                if (textLower.contains(" n ") || textLower.contains(" n,") || textLower.contains(" n\n")) vars.add("N");
                if (textLower.contains(" m ") || textLower.contains(" m,") || textLower.contains(" m\n")) vars.add("M");
                if (textLower.contains(" k ") || textLower.contains(" k,") || textLower.contains(" k\n")) vars.add("K");
            }
            if (vars.isEmpty()) {
                vars.add("N");
            }
            
            for (String name : vars) {
                Bounds bounds = boundsFor(dto.getSemanticSpec(), name, defaultMin(name), defaultMax(name));
                ObjectNode field = fields.addObject();
                field.put("name", name);
                field.put("type", "int");
                field.put("min", bounds.min());
                field.put("max", bounds.max());
            }
            
            ObjectNode raw = lines.addObject();
            raw.put("kind", "raw_lines");
            raw.put("length", "1");
            
            dto.setInputSchema(schema);
        }
        
        if (dto.getTestPlan() == null) {
            AiResponseDTO.TestPlan plan = new AiResponseDTO.TestPlan();
            plan.setProblemType("generic_fallback_input");
            plan.setIntendedSolution("Solve the problem within time and memory limits.");
            
            AiResponseDTO.TestFamily family = new AiResponseDTO.TestFamily();
            family.setName("general_stress");
            family.setDifficulty("stress");
            family.setTarget(List.of("performance", "correctness"));
            family.setConstraints("general input size near limits");
            family.setExpected("correct solution output");
            family.setReason("verifies baseline correctness");
            
            plan.setTestFamilies(List.of(family));
            dto.setTestPlan(plan);
        }
    }

    private record Bounds(long min, long max) {}
}
