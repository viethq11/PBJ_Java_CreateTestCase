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
            return stripMarkdownFences(root.path("response").asText(""));
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

                JSON spec:
                %s

                You must follow this contract strictly:
                1. Read two command line arguments:
                   argv[1] = seed
                   argv[2] = size_level
                   size_level in {"small", "medium", "large", "stress"}
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
                       string size = argv[2];
                       mt19937 rng(seed);
                       // choose constraints by size
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
                - Never print node 0.
                - For graph nodes, all node ids must be generated in 1..N.
                - If a field is binary, print only 0 or 1.
                - Keep M inside constraints.
                - Do not use unbounded retry loops.
                - The generator must accept argv[1]=seed and argv[2]=size_level.
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
}
