package com.pbj.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "problems")
@Data
@NoArgsConstructor
public class Problem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String constraints;

    @Column(name = "input_format", columnDefinition = "TEXT")
    private String inputFormat;

    @Column(name = "output_format", columnDefinition = "TEXT")
    private String outputFormat;

    @Column(name = "time_limit")
    private Integer timeLimit; // in milliseconds

    @Column(name = "memory_limit")
    private Integer memoryLimit; // in megabytes

    private LocalDateTime createdAt;
    
    @Column(columnDefinition = "TEXT")
    private String checkerCode;

    @Column(name = "validator_code", columnDefinition = "LONGTEXT")
    private String validatorCode;

    @Column(name = "test_plan", columnDefinition = "LONGTEXT")
    private String testPlan;

    @Column(name = "accepted_solution_code", columnDefinition = "LONGTEXT")
    private String acceptedSolutionCode;

    @Column(name = "accepted_solution_language")
    private String acceptedSolutionLanguage;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (timeLimit == null) timeLimit = 2000;
        if (memoryLimit == null) memoryLimit = 256;
    }
}
