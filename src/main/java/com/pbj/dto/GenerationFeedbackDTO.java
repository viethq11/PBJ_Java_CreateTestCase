package com.pbj.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class GenerationFeedbackDTO {
    private String stage;
    private String summary;
    private String clarification;
    private List<String> completedStages;
    private List<String> missingInformation;
}
