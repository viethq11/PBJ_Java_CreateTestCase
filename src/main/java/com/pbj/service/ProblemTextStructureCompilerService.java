package com.pbj.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pbj.dto.SemanticSpecDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ProblemTextStructureCompilerService {
    private static final Pattern HEADER_LINE = Pattern.compile(
            "(?i)first line contains .*?integers?[,\\s]+([A-Za-z_][A-Za-z0-9_]*)\\s+and\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern REPEAT_LINE = Pattern.compile(
            "(?i)next\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+lines?\\s+contain");
    private static final Pattern COMMAND_FORM = Pattern.compile(
            "\\b([A-Z][A-Z0-9_]*)\\s+((?:[A-Za-z_][A-Za-z0-9_]*\\s*)+)");

    private final ObjectMapper objectMapper;

    public void enrichMissingInputModel(SemanticSpecDTO spec, String problemText) {
        if (spec == null || problemText == null || problemText.isBlank() || hasUsableInputModel(spec.getInputModel())) {
            return;
        }

        List<String> header = detectHeader(problemText);
        String repeat = detectRepeat(problemText);
        List<CommandVariant> variants = detectCommandVariants(problemText);
        if (header.isEmpty() || repeat.isBlank() || variants.size() < 2) {
            return;
        }

        ObjectNode model = objectMapper.createObjectNode();
        model.put("type", looksMultiTest(problemText) ? "multi_test_command_based" : "single_case_command_based");
        if (looksMultiTest(problemText)) {
            model.put("test_count", "T");
        }

        ArrayNode sections = model.putArray("sections");
        ObjectNode headerSection = sections.addObject();
        ArrayNode readVariables = headerSection.putArray("read_variables");
        header.forEach(readVariables::add);

        ObjectNode loopSection = sections.addObject();
        ObjectNode loop = loopSection.putObject("loop");
        loop.put("count_variable", repeat);
        ArrayNode body = loop.putArray("body");
        ArrayNode commands = body.addObject().putArray("command_variants");
        for (CommandVariant variant : variants) {
            ObjectNode node = commands.addObject();
            node.put("keyword", variant.keyword());
            ArrayNode args = node.putArray("args");
            variant.args().forEach(args::add);
        }

        spec.setInputModel(model);
    }

    private boolean hasUsableInputModel(JsonNode model) {
        if (model == null || model.isNull() || model.isMissingNode()) return false;
        JsonNode sections = model.path("sections");
        JsonNode blocks = model.path("blocks");
        return (sections.isArray() && !sections.isEmpty()) || (blocks.isArray() && !blocks.isEmpty());
    }

    private List<String> detectHeader(String text) {
        Matcher matcher = HEADER_LINE.matcher(text);
        if (!matcher.find()) return List.of();
        return List.of(matcher.group(1), matcher.group(2));
    }

    private String detectRepeat(String text) {
        Matcher matcher = REPEAT_LINE.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private boolean looksMultiTest(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("first line contains an integer t")
                || lower.contains("number of test cases");
    }

    private List<CommandVariant> detectCommandVariants(String text) {
        Set<String> seen = new LinkedHashSet<>();
        List<CommandVariant> out = new ArrayList<>();
        Matcher matcher = COMMAND_FORM.matcher(text);
        while (matcher.find()) {
            String keyword = matcher.group(1);
            if (!isLikelyCommand(keyword)) continue;

            List<String> args = new ArrayList<>();
            for (String token : matcher.group(2).trim().split("\\s+")) {
                if (!token.matches("[A-Za-z_][A-Za-z0-9_]*")) break;
                args.add(token);
            }
            if (args.isEmpty()) continue;

            String signature = keyword + "|" + String.join(",", args);
            if (seen.add(signature)) {
                out.add(new CommandVariant(keyword, args));
            }
        }
        return out;
    }

    private boolean isLikelyCommand(String keyword) {
        return keyword.length() >= 3
                && !keyword.equals("INPUT")
                && !keyword.equals("OUTPUT")
                && !keyword.equals("TIME")
                && !keyword.equals("PROBLEM");
    }

    private record CommandVariant(String keyword, List<String> args) {}
}
