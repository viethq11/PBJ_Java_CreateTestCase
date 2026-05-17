package com.pbj.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.BufferedReader;

@Entity
@Table(name = "testcases")
@Data
@NoArgsConstructor
public class TestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id", nullable = false)
    private Problem problem;

    /**
     * Path to the .in file stored on disk.
     * New testcases use this path.
     */
    @Column(name = "input_path")
    private String inputPath;

    @Column(name = "output_path")
    private String outputPath;

    /**
     * LEGACY COLUMNS: Support displaying old data before migration to files.
     */
    @Column(name = "input_data", columnDefinition = "TEXT")
    private String legacyInput;

    @Column(name = "output_data", columnDefinition = "TEXT")
    private String legacyOutput;

    @Column(name = "is_sample")
    private Boolean isSample;

    // -----------------------------------------------------------------------
    // Memory Cache
    // -----------------------------------------------------------------------

    @Transient
    private transient String cachedInput;

    @Transient
    private transient String cachedOutput;

    /** 
     * Returns input content. 
     * Priority: 1. Cached in-memory, 2. Read from file-path, 3. Fallback to legacy DB column.
     */
    public String getInputData() {
        if (cachedInput != null) return cachedInput;
        
        if (inputPath != null && !inputPath.isBlank()) {
            cachedInput = readFile(inputPath);
            return cachedInput;
        }
        
        return legacyInput; // Fallback for old records
    }

    public String getOutputData() {
        if (cachedOutput != null) return cachedOutput;
        
        if (outputPath != null && !outputPath.isBlank()) {
            cachedOutput = readFile(outputPath);
            return cachedOutput;
        }
        
        return legacyOutput; // Fallback for old records
    }

    public void setInputData(String data) {
        this.cachedInput = data;
    }

    public void setOutputData(String data) {
        this.cachedOutput = data;
    }

    public String getInputPreview() {
        return readPreview(inputPath, legacyInput);
    }

    public String getOutputPreview() {
        return readPreview(outputPath, legacyOutput);
    }

    public boolean isInputPreviewTruncated() {
        return isPreviewTruncated(inputPath, legacyInput);
    }

    public boolean isOutputPreviewTruncated() {
        return isPreviewTruncated(outputPath, legacyOutput);
    }

    private static String readFile(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path));
        } catch (Exception e) {
            // Log once, return empty
            return "Error reading file: " + e.getMessage();
        }
    }

    private static final int PREVIEW_MAX_CHARS = 8_000;

    private static String readPreview(String path, String fallback) {
        if (path == null || path.isBlank()) {
            return previewText(fallback);
        }

        java.nio.file.Path file = java.nio.file.Path.of(path);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = java.nio.file.Files.newBufferedReader(file)) {
            int ch;
            while ((ch = reader.read()) != -1) {
                if (sb.length() >= PREVIEW_MAX_CHARS) {
                    sb.append("\n... [truncated testcase preview]");
                    break;
                }
                sb.append((char) ch);
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error reading file preview: " + e.getMessage();
        }
    }

    private static String previewText(String value) {
        if (value == null) return null;
        if (value.length() <= PREVIEW_MAX_CHARS) return value;
        return value.substring(0, PREVIEW_MAX_CHARS) + "\n... [truncated testcase preview]";
    }

    private static boolean isPreviewTruncated(String path, String fallback) {
        try {
            if (path != null && !path.isBlank()) {
                return java.nio.file.Files.size(java.nio.file.Path.of(path)) > PREVIEW_MAX_CHARS;
            }
        } catch (Exception ignore) {
            return false;
        }
        return fallback != null && fallback.length() > PREVIEW_MAX_CHARS;
    }
}
