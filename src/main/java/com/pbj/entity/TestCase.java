package com.pbj.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    private static String readFile(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path));
        } catch (Exception e) {
            // Log once, return empty
            return "Error reading file: " + e.getMessage();
        }
    }
}
