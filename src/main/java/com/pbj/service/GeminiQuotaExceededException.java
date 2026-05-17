package com.pbj.service;

public class GeminiQuotaExceededException extends IllegalStateException {
    public GeminiQuotaExceededException(String message) {
        super(message);
    }

    public GeminiQuotaExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
