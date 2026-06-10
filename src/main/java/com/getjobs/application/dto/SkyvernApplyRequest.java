package com.getjobs.application.dto;

import java.util.List;

public record SkyvernApplyRequest(
        String goal,
        String targetUrl,
        String companyName,
        List<String> companyNames,
        String resumePath,
        String skyvernBaseUrl,
        String skyvernApiKey,
        String engine,
        String modelName,
        Integer maxSteps,
        Integer maxApplications,
        Boolean uploadResume,
        Boolean autoSubmit,
        String browserSessionId,
        String browserAddress,
        String extraNotes
) {
}
