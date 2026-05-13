package com.pbj.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class OllamaGeneratorService {

    @Value("${ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ai.ollama.model:deepseek-coder:6.7b}")
    private String ollamaModel;

    @Value("${ai.ollama.timeout-seconds:90}")
    private int timeoutSeconds;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String generateGenerator(String generatorSpecJson) {
        return callOllama(buildGeneratorPrompt(generatorSpecJson));
    }

    public String repairGenerator(String generatorSpecJson, String previousGenerator, String validationError) {
        return callOllama(buildRepairPrompt(generatorSpecJson, previousGenerator, validationError));
    }

    private String callOllama(String prompt) {
        String url = ollamaBaseUrl + "/api/generate";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = Map.of(
                "model", ollamaModel,
                "prompt", prompt,
                "stream", false,
                "options", Map.of("temperature", 0)
        );

        try {
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);
            ResponseEntity<String> response = buildRestTemplate().postForEntity(url, entity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            return sanitizeCppGenerator(root.path("response").asText(""));
        } catch (Exception e) {
            throw new RuntimeException("Ollama generator failed: " + e.getMessage(), e);
        }
    }

    private String buildGeneratorPrompt(String generatorSpecJson) {
        return """
                You are a competitive programming testcase generator engineer.

                Your task is ONLY to write a deterministic C++17 generator.

                DO NOT solve the problem.
                DO NOT output raw testcases.
                DO NOT invent constraints.
                DO NOT use unbounded while loops.
                DO NOT use recursion unless necessary.
                DO NOT generate invalid input.

                Generate C++17 generator using ONLY this JSON spec.
                If information is missing, choose the safest valid interpretation.
                The input format is authoritative. Match it exactly and do not add extra sections.
                The validator rules are authoritative. Every generated testcase must pass them.

                JSON spec:
                %s

                You must follow this contract strictly:
                1. Read two command line arguments:
                   argv[1] = seed
                   argv[2] = profile_name
                   profile_name is usually one of:
                   {"edge_boundary", "overflow_int32", "overflow_int64_if_relevant",
                    "anti_greedy_small", "tie_breaking", "random_small",
                    "random_large", "stress_performance", "adversarial_structure"}
                   You may internally map these to small/medium/large/stress buckets, but you must
                   also make the output shape react to the profile objective when possible.
                2. Output exactly one valid testcase.
                3. All generated values must satisfy the problem constraints.
                4. For graph problems:
                   - Nodes must be 1-indexed: 1 <= u, v <= N
                   - No node 0 is allowed
                   - Edge type/weight must follow the statement exactly
                   - If edge type is binary, it must be only 0 or 1
                   - M must be within allowed bounds
                   - Avoid duplicate edges unless the statement allows them
                   - Avoid self-loops unless the statement allows them
                5. Generator must finish under 1 second.
                6. Do not use random retry loops that may run forever.
                   Bad: while(edges.size() < M) { random edge until unique }
                   Good: precompute all possible edges, shuffle, take first M.
                7. Use this structure:
                   #include <bits/stdc++.h>
                   using namespace std;
                   int main(int argc, char** argv) {
                       int seed = stoi(argv[1]);
                       string profile = argv[2];
                       mt19937 rng(seed);
                       // choose constraints by profile
                       // generate valid input
                       // print testcase
                   }

                Before finalizing the generator, mentally verify:
                - Does every printed number satisfy constraints?
                - Are all nodes 1-indexed?
                - Is M valid?
                - Are binary values only 0 or 1?
                - Can the generator timeout?
                - Can any loop become infinite?

                If any answer is no, rewrite the generator.
                Do not include markdown fences, comments outside code, special tokens, BOS/EOS markers, or prose.
                Return ONLY the C++17 generator code.
                """.formatted(generatorSpecJson == null ? "{}" : generatorSpecJson);
    }

    private String buildRepairPrompt(String generatorSpecJson, String previousGenerator, String validationError) {
        return """
                Your previous C++17 generator failed validation.

                JSON spec:
                %s

                Validation error:
                %s

                Previous generator:
                %s

                Fix the generator.
                Rules:
                - Return only corrected C++17 code.
                - Match the JSON input_format exactly. Do not add arrays, edges, queries, or sections unless the input_format requires them.
                - The corrected generator must pass the validator for boundary, random, overflow, greedy, and stress-style profiles.
                - Never print node 0.
                - For graph nodes, all node ids must be generated in 1..N.
                - If a field is binary, print only 0 or 1.
                - Keep M inside constraints.
                - Do not use unbounded retry loops.
                - The generator must accept argv[1]=seed and argv[2]=profile_name.
                - Do not include markdown fences, special tokens, BOS/EOS markers, or prose.
                """.formatted(
                generatorSpecJson == null ? "{}" : generatorSpecJson,
                validationError == null ? "Unknown validation error" : validationError,
                previousGenerator == null ? "" : previousGenerator);
    }

    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutSeconds * 1000);
        factory.setReadTimeout(timeoutSeconds * 1000);
        return new RestTemplate(factory);
    }

    private String stripMarkdownFences(String text) {
        if (text == null) return "";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?s)```(?:\\w+)?\\s*\\n?(.*?)```");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return text.trim();
    }

    private String sanitizeCppGenerator(String text) {
        String code = stripMarkdownFences(text);
        if (code.isBlank()) return code;

        code = code.replaceAll("(?s)<[^>]*(begin|end|bos|eos)[^>]*>", "");
        code = code.replace("<s>", "").replace("</s>", "");
        code = code.replaceAll("[^\\x00-\\x7F]", "");

        int includeIdx = code.indexOf("#include");
        int mainIdx = code.indexOf("int main");
        if (includeIdx >= 0) {
            code = code.substring(includeIdx);
        } else if (mainIdx >= 0) {
            code = "#include <bits/stdc++.h>\nusing namespace std;\n" + code.substring(mainIdx);
        }

        int lastBrace = code.lastIndexOf('}');
        if (lastBrace >= 0 && lastBrace + 1 < code.length()) {
            code = code.substring(0, lastBrace + 1);
        }

        code = code.trim();
        if (containsModelSpecialToken(code)) {
            throw new RuntimeException("Generator contains model special tokens after sanitization.");
        }
        return code;
    }

    private boolean containsModelSpecialToken(String code) {
        if (code == null) return false;
        String lower = code.toLowerCase();
        return code.contains("<|")
                || code.contains("|>")
                || code.contains("<｜")
                || code.contains("｜>")
                || lower.contains("begin_of_sentence")
                || lower.contains("end_of_sentence")
                || lower.contains("beginofsentence")
                || lower.contains("endofsentence");
    }
}
