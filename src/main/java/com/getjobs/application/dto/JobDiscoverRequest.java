package com.getjobs.application.dto;

import java.util.List;

public record JobDiscoverRequest(
    String goal,
    String resumePath,
    List<String> companyNames,
    String extraNotes
) {}
