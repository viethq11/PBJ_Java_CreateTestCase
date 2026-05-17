package com.pbj.service;

import com.pbj.dto.GenerationFeedbackDTO;

public class GenerationNeedsInputException extends IllegalStateException {
    private final GenerationFeedbackDTO feedback;

    public GenerationNeedsInputException(GenerationFeedbackDTO feedback, Throwable cause) {
        super(feedback == null ? null : feedback.getSummary(), cause);
        this.feedback = feedback;
    }

    public GenerationFeedbackDTO getFeedback() {
        return feedback;
    }
}
