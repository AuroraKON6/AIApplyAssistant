package com.getjobs.application.service;

import java.util.Map;
import java.util.Set;

public final class PlatformClassifier {

    private PlatformClassifier() {}

    public record PlatformClass(String channelType, String deliveryDifficulty) {}

    private static final Set<String> ATS_DOMAINS = Set.of(
            "greenhouse.io", "lever.co", "ashbyhq.com",
            "workday.com", "smartrecruiters.com", "myworkdayjobs.com",
            "icims.com", "jobvite.com", "taleo.net", "successfactors.com"
    );

    private static final Set<String> JOB_BOARD_DOMAINS = Set.of(
            "liepin.com", "51job.com", "zhaopin.com", "zhipin.com", "bosszhipin.com",
            "linkedin.com", "indeed.com", "lagou.com",
            "boss.com", "recruit.net", "careerbuilder.com", "monster.com"
    );

    private static final Map<String, PlatformClass> SOURCE_MAP = Map.of(
            "Boss直聘", new PlatformClass("job_board", "login_required"),
            "猎聘", new PlatformClass("job_board", "login_required"),
            "前程无忧", new PlatformClass("job_board", "login_required"),
            "51job", new PlatformClass("job_board", "login_required"),
            "智联招聘", new PlatformClass("job_board", "login_required"),
            "LinkedIn", new PlatformClass("job_board", "login_required"),
            "Indeed", new PlatformClass("job_board", "login_required")
    );

    public static PlatformClass classify(String url, String source) {
        if (url != null) {
            String lower = url.toLowerCase();
            for (String domain : ATS_DOMAINS) {
                if (lower.contains(domain)) {
                    return new PlatformClass("ats", "auto");
                }
            }
            if (lower.contains("/careers") || lower.contains("/jobs") || lower.contains("/join")) {
                return new PlatformClass("company_site", "login_required");
            }
            for (String domain : JOB_BOARD_DOMAINS) {
                if (lower.contains(domain)) {
                    return new PlatformClass("job_board", "login_required");
                }
            }
        }

        if (source != null) {
            PlatformClass fromSource = SOURCE_MAP.get(source.trim());
            if (fromSource != null) return fromSource;
        }

        return new PlatformClass("manual_required", "manual");
    }
}
