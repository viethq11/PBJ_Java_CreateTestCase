package com.pbj.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class OcrCleanerService {
    public String clean(String rawText, String fallbackDescription) {
        String source = rawText == null || rawText.isBlank() ? fallbackDescription : rawText;
        if (source == null) return "";

        String normalized = source
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u00a0', ' ')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("(?m)^\\s+", "")
                .replaceAll("(?m)\\s+$", "");

        String[] paragraphs = normalized.split("\\n\\s*\\n+");
        List<String> cleanedParagraphs = new ArrayList<>();
        for (String paragraph : paragraphs) {
            String cleaned = joinWrappedLines(paragraph);
            if (!cleaned.isBlank()) {
                cleanedParagraphs.add(cleaned);
            }
        }
        return String.join("\n\n", cleanedParagraphs).trim();
    }

    private String joinWrappedLines(String paragraph) {
        String[] lines = paragraph.split("\\n+");
        StringBuilder out = new StringBuilder();
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isBlank()) continue;
            if (out.length() == 0 || shouldKeepLineBreak(line)) {
                if (out.length() > 0) out.append('\n');
                out.append(line);
            } else {
                out.append(' ').append(line);
            }
        }
        return out.toString();
    }

    private boolean shouldKeepLineBreak(String line) {
        return line.matches("(?i)^(input|output|example|sample|constraints?)\\b.*")
                || line.matches("^[-*]\\s+.*")
                || line.matches("^\\d+[.)]\\s+.*");
    }
}
