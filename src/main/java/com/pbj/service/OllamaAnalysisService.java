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
public class OllamaAnalysisService {

    @Value("${ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ai.ollama.model:llama3.1:8b}")
    private String ollamaModel;

    @Value("${ai.ollama.timeout-seconds:90}")
    private int timeoutSeconds;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String analyzeProblem(String problemText) {
        String prompt = buildAnalysisPrompt(problemText);
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
            String analysis = stripMarkdownFences(root.path("response").asText(""));
            return analysis.isBlank() ? "{}" : analysis;
        } catch (Exception e) {
            throw new RuntimeException("Ollama analysis failed: " + e.getMessage(), e);
        }
    }

    private String buildAnalysisPrompt(String problemText) {
        return """
                You are a local competitive-programming problem analyzer.
                Return ONLY a short valid JSON object. No markdown, no explanation.

                Extract:
                {
                  "problem_type": "short category",
                  "input_format": "concise extracted input format",
                  "output_format": "concise extracted output format",
                  "constraints": "all numeric and structural constraints",
                  "algorithm_type": "likely intended algorithm family",
                  "intended_complexity": "expected time and memory complexity",
                  "likely_wrong_solutions": ["greedy local optimum, off-by-one, int overflow, assumes connected graph, etc."],
                  "required_profiles": ["SMALL_EXHAUSTIVE, BOUNDARY_MIN, RANDOM_LARGE, ADVERSARIAL_GREEDY, STRESS_PERFORMANCE, etc."],
                  "edge_risks": ["boundary, overflow, disconnected, duplicates, etc."],
                  "slow_wrong_approaches": ["brute force or TLE approaches likely to pass weak tests"]
                }

                Problem:
                %s
                """.formatted(problemText == null ? "" : problemText);
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
