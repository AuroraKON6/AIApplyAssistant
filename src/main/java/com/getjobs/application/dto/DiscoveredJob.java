package com.getjobs.application.dto;

public record DiscoveredJob(
    String id,
    String company,
    String title,
    String location,
    String url,
    String matchReason,
    String source,
    String checkedAt,
    String confidence,
    String evidenceText,
    String channelType,
    String deliveryDifficulty,
    String verificationStatus,
    String verificationNote,
    String targetCompany,
    String officialWebsite,
    String careersPage,
    String companySearchStatus
) {}
