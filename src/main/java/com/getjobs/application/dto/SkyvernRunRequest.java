package com.getjobs.application.dto;

public record SkyvernRunRequest(
        String runId,
        String skyvernBaseUrl,
        String skyvernApiKey
) {
}
