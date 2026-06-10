package com.getjobs.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.dto.SkyvernApplyRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkyvernService {
    private static final String DEFAULT_SKYVERN_BASE_URL = "http://127.0.0.1:8001";
    private static final String DEFAULT_ENGINE = "skyvern-2.0";
    private static final String DEFAULT_MODEL_NAME = "";
    private static final String DEFAULT_LLM_PROVIDER = "openai-compatible";
    private static final String DEFAULT_LLM_BASE_URL = "";
    private static final boolean DEFAULT_LLM_SUPPORTS_VISION = false;
    private static final int DEFAULT_LLM_MAX_TOKENS = 4096;
    private static final String DEFAULT_BROWSER_ADDRESS = "";
    private static final int DEFAULT_MAX_STEPS = 0;
    private static final int DEFAULT_MAX_APPLICATIONS = 0;
    private static final int PRACTICAL_UNLIMITED_STEPS = 10000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ConfigService configService;
    private final SkyvernProcessManager processManager;
    private final ApplicantProfileService applicantProfileService;

    private final Set<String> activeRunIds = ConcurrentHashMap.newKeySet();
    private volatile boolean pendingSkyvernRestart = false;

    public Map<String, Object> defaults() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skyvernBaseUrl", configuredValue("SKYVERN_BASE_URL", DEFAULT_SKYVERN_BASE_URL));
        result.put("resumePath", configuredValue("RESUME_PATH", ""));
        result.put("engine", configuredValue("SKYVERN_ENGINE", DEFAULT_ENGINE));
        result.put("modelName", configuredValue("SKYVERN_MODEL_NAME", configuredValue("LLM_MODEL_NAME", DEFAULT_MODEL_NAME)));
        result.put("browserAddress", configuredValue("SKYVERN_BROWSER_ADDRESS", DEFAULT_BROWSER_ADDRESS));
        result.put("maxSteps", DEFAULT_MAX_STEPS);
        result.put("maxApplications", DEFAULT_MAX_APPLICATIONS);
        result.put("skyvernApiKeyConfigured", !configuredValue("SKYVERN_API_KEY", "").isBlank());
        return result;
    }

    public Map<String, Object> llmConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        String provider = configuredValue("LLM_PROVIDER", DEFAULT_LLM_PROVIDER);
        String apiKey = configuredValue("LLM_API_KEY", "");
        result.put("provider", provider);
        result.put("modelName", configuredValue("LLM_MODEL_NAME", providerDefaultModel(provider)));
        result.put("baseUrl", configuredValue("LLM_BASE_URL", providerDefaultBaseUrl(provider)));
        result.put("apiKeyConfigured", !apiKey.isBlank());
        result.put("supportsVision", parseBoolean(configuredValue("LLM_SUPPORTS_VISION", ""), DEFAULT_LLM_SUPPORTS_VISION));
        result.put("maxTokens", parseInt(configuredValue("LLM_MAX_TOKENS", ""), DEFAULT_LLM_MAX_TOKENS));
        result.put("skyvernEnvPath", skyvernEnvPath().toString());
        return result;
    }

    public Map<String, Object> saveLlmConfig(Map<String, Object> request) {
        if (request == null) {
            throw new IllegalArgumentException("AI model config is required.");
        }

        String provider = nonBlank(valueOf(request.get("provider")), DEFAULT_LLM_PROVIDER).toLowerCase();
        String modelName = nonBlank(valueOf(request.get("modelName")), providerDefaultModel(provider));
        String baseUrl = nonBlank(valueOf(request.get("baseUrl")), providerDefaultBaseUrl(provider));
        String apiKey = valueOf(request.get("apiKey"));
        if (isBlank(apiKey)) {
            apiKey = configuredValue("LLM_API_KEY", "");
        }
        boolean supportsVision = parseBoolean(valueOf(request.get("supportsVision")), DEFAULT_LLM_SUPPORTS_VISION);
        int maxTokens = parseInt(valueOf(request.get("maxTokens")), DEFAULT_LLM_MAX_TOKENS);

        if (isBlank(modelName)) {
            throw new IllegalArgumentException("Model name is required.");
        }
        if (isBlank(baseUrl)) {
            throw new IllegalArgumentException("Model API URL is required.");
        }
        if (isBlank(apiKey)) {
            throw new IllegalArgumentException("API Key is required.");
        }

        configService.upsertConfig("LLM_PROVIDER", provider, "ai", "AI模型供应商");
        configService.upsertConfig("LLM_MODEL_NAME", modelName, "ai", "AI模型名称");
        configService.upsertConfig("LLM_BASE_URL", baseUrl, "ai", "OpenAI兼容API地址");
        configService.upsertConfig("LLM_API_KEY", apiKey, "ai", "AI模型API Key");
        configService.upsertConfig("LLM_SUPPORTS_VISION", String.valueOf(supportsVision), "ai", "模型是否支持视觉输入");
        configService.upsertConfig("LLM_MAX_TOKENS", String.valueOf(maxTokens), "ai", "模型输出Token上限");
        configService.upsertConfig("SKYVERN_MODEL_NAME", modelName, "skyvern", "Skyvern任务默认模型");

        Path envPath = writeSkyvernEnv(provider, modelName, baseUrl, apiKey, supportsVision, maxTokens);

        boolean applied = false;
        String applyMessage = "AI配置已生效，可以直接开始查找岗位。";
        if (hasActiveRuns()) {
            pendingSkyvernRestart = true;
            applyMessage = "配置已保存，当前有正在投递的任务，任务结束后会自动应用新配置。";
        } else {
            try {
                processManager.restartIfNeeded();
                applied = true;
            } catch (Exception e) {
                log.warn("Failed to restart Skyvern after config save: {}", e.getMessage());
                applyMessage = "配置已保存，但浏览器执行服务未重新连接，请稍后重试。";
            }
        }

        Map<String, Object> result = llmConfig();
        result.put("applied", applied);
        result.put("message", applyMessage);
        result.put("skyvernEnvPath", envPath.toString());
        return result;
    }

    public Map<String, Object> health(String skyvernBaseUrl, String skyvernApiKey) {
        String baseUrl = resolveBaseUrl(skyvernBaseUrl);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baseUrl", baseUrl);
        try {
            BlockingHttpClient.Response response = BlockingHttpClient.get(
                    baseUrl + "/openapi.json",
                    skyvernHeaders(skyvernApiKey),
                    5
            );
            result.put("reachable", response.statusCode() >= 200 && response.statusCode() < 500);
            result.put("statusCode", response.statusCode());
        } catch (Exception e) {
            result.put("reachable", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    public Map<String, Object> startApplication(SkyvernApplyRequest request) {
        if (request == null || isBlank(request.goal())) {
            throw new IllegalArgumentException("Please describe the job or internship you want.");
        }

        String targetUrl = request.targetUrl();
        String baseUrl = resolveBaseUrl(request.skyvernBaseUrl());
        String apiKey = resolveApiKey(request.skyvernApiKey());
        String resumePath = resolveResumePath(request.resumePath());
        UploadInfo uploadInfo = null;

        boolean shouldUploadResume = request.uploadResume() == null || request.uploadResume();
        if (shouldUploadResume && !isBlank(resumePath)) {
            uploadInfo = uploadResume(baseUrl, apiKey, resumePath);
        }

        String prompt = buildApplicationPrompt(request, uploadInfo, resumePath);
        JSONObject body = new JSONObject();
        body.put("prompt", prompt);
        body.put("title", buildTitle(request));
        body.put("engine", nonBlank(request.engine(), configuredValue("SKYVERN_ENGINE", DEFAULT_ENGINE)));
        String modelName = nonBlank(request.modelName(), configuredValue("SKYVERN_MODEL_NAME", configuredValue("LLM_MODEL_NAME", DEFAULT_MODEL_NAME)));
        if (!isBlank(modelName)) {
            body.put("model", new JSONObject(Map.of("model_name", modelName)));
        }
        body.put("proxy_location", "NONE");
        body.put("data_extraction_schema", new JSONObject(applicationOutputSchema()));

        String url = normalizeUrl(request.targetUrl());
        if (!isBlank(url)) {
            body.put("url", url);
        }
        if (!isBlank(request.browserSessionId())) {
            body.put("browser_session_id", request.browserSessionId().trim());
        }
        String browserAddress = nonBlank(request.browserAddress(), configuredValue("SKYVERN_BROWSER_ADDRESS", ""));
        if (!isBlank(browserAddress)) {
            body.put("browser_address", browserAddress);
        }

        Map<String, Object> skyvernRun = postJson(baseUrl + "/v1/run/tasks", apiKey, body);
        skyvernRun.put("skyvernBaseUrl", baseUrl);
        skyvernRun.put("resumeUploaded", uploadInfo != null);
        if (uploadInfo != null) {
            skyvernRun.put("resumeFileUrl", uploadInfo.presignedUrl());
            skyvernRun.put("resumeS3Uri", uploadInfo.s3Uri());
        }
        skyvernRun.put("effectivePrompt", prompt);

        String runId = valueOf(skyvernRun.get("run_id"));
        if (!isBlank(runId)) {
            activeRunIds.add(runId);
            attachRunViewingLinks(skyvernRun, baseUrl, runId);
        }
        return skyvernRun;
    }

    public Map<String, Object> getRun(String runId, String skyvernBaseUrl, String skyvernApiKey) {
        if (isBlank(runId)) {
            throw new IllegalArgumentException("runId is required.");
        }
        String baseUrl = resolveBaseUrl(skyvernBaseUrl);
        Map<String, Object> result = getJson(baseUrl + "/v1/runs/" + runId.trim(), resolveApiKey(skyvernApiKey));
        attachRunViewingLinks(result, baseUrl, runId.trim());
        cleanFinishedRun(runId, result);
        return result;
    }

    public Map<String, Object> cancelRun(String runId, String skyvernBaseUrl, String skyvernApiKey) {
        if (isBlank(runId)) {
            throw new IllegalArgumentException("runId is required.");
        }
        String baseUrl = resolveBaseUrl(skyvernBaseUrl);
        Map<String, Object> result = postJson(baseUrl + "/v1/runs/" + runId.trim() + "/cancel", resolveApiKey(skyvernApiKey), new JSONObject());
        activeRunIds.remove(runId.trim());
        tryPendingSkyvernRestart();
        return result;
    }

    private UploadInfo uploadResume(String baseUrl, String apiKey, String resumePath) {
        Path path = Path.of(resumePath.trim()).toAbsolutePath().normalize();
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Resume file does not exist: " + path);
        }

        try {
            String fileName = sanitizeFileName(path.getFileName().toString());
            String contentType = Files.probeContentType(path);
            if (isBlank(contentType)) {
                contentType = "application/octet-stream";
            }

            byte[] fileBytes = Files.readAllBytes(path);

            BlockingHttpClient.Response response = BlockingHttpClient.multipartFile(
                    baseUrl + "/v1/upload_file",
                    skyvernHeaders(apiKey),
                    "file",
                    fileName,
                    contentType,
                    fileBytes,
                    120
            );
            Map<String, Object> parsed = parseResponse(response);
            String s3Uri = String.valueOf(parsed.getOrDefault("s3_uri", ""));
            String presignedUrl = String.valueOf(parsed.getOrDefault("presigned_url", ""));
            if (isBlank(presignedUrl)) {
                throw new IllegalStateException("Skyvern did not return a resume file URL.");
            }
            return new UploadInfo(s3Uri, presignedUrl);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read resume file: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> postJson(String url, String apiKey, JSONObject body) {
        return send(BlockingHttpClient.postJson(url, skyvernHeaders(apiKey), body.toString(), 120));
    }

    private Map<String, Object> getJson(String url, String apiKey) {
        return send(BlockingHttpClient.get(url, skyvernHeaders(apiKey), 30));
    }

    private Map<String, Object> send(BlockingHttpClient.Response response) {
        try {
            return parseResponse(response);
        } catch (Exception e) {
            throw new RuntimeException("Skyvern request failed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> parseResponse(BlockingHttpClient.Response response) {
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Skyvern returned HTTP " + response.statusCode() + ": " + body);
        }
        if (body.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return OBJECT_MAPPER.readValue(body, new TypeReference<>() {});
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("raw", body);
            return fallback;
        }
    }

    private Map<String, String> skyvernHeaders(String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        if (!isBlank(apiKey)) {
            headers.put("x-api-key", apiKey);
        }
        return headers;
    }

    private String buildApplicationPrompt(SkyvernApplyRequest request, UploadInfo uploadInfo, String resumePath) {
        boolean autoSubmit = Boolean.TRUE.equals(request.autoSubmit());
        String normalizedTargetUrl = normalizeUrl(request.targetUrl());
        boolean hasSpecificTarget = !isBlank(normalizedTargetUrl);

        List<String> lines = new ArrayList<>();
        List<String> companyHints = companyHints(request);
        lines.add("You are a careful autonomous job application agent.");
        lines.add("User goal: " + request.goal().trim());
        if (hasSpecificTarget) {
            lines.add("Primary workflow: apply to the specific selected role at the target URL.");
            lines.add("1. Open the target URL and stay focused on this selected role. Do not perform a fresh broad job search unless the URL redirects to a search/list page and you must click into the matching role.");
            lines.add("2. Verify the page shows a real currently recruiting job or internship. Check visible role title, company, location, and application action.");
            lines.add("3. Apply only to this selected role, or to the closest matching role on the same site if the URL lands on a list page.");
            lines.add("4. If the selected role is closed, unrelated, hidden behind unavailable content, or cannot be verified, stop and report the blocker instead of applying elsewhere.");
            lines.add("Target URL: " + normalizedTargetUrl);
        } else {
            lines.add("Primary workflow:");
            lines.add("1. Discover currently open jobs or internships that match the user's goal.");
            lines.add("2. Identify companies with matching openings, using reputable job boards, search engines, official company career pages, and public recruitment pages.");
            lines.add("3. Prefer official company career pages when available; otherwise use reputable job boards.");
            lines.add("4. Open each matching role, verify it is currently recruiting, and apply only if the role clearly matches the user's goal.");
        }
        if (!isBlank(request.companyName())) {
            lines.add(hasSpecificTarget ? "Expected company for the selected role: " + request.companyName().trim() : "Optional company hint: " + request.companyName().trim());
        }
        if (!companyHints.isEmpty()) {
            if (hasSpecificTarget) {
                lines.add("Background company leads from the user are available, but do not apply to them during this specific-role task:");
                lines.add(String.join("; ", companyHints));
            } else {
                lines.add("Optional company list from user-provided spreadsheet or pasted text:");
                lines.add(String.join("; ", companyHints));
                lines.add("For these company hints, find each company's official website or official careers/recruitment page before applying. Treat them as leads, not as the only possible companies unless they contain enough matching openings.");
            }
        }
        if (hasSpecificTarget) {
            if (isBossUrl(request.targetUrl())) {
                lines.add("The starting website is Boss直聘 / zhipin.com. This site may show anti-bot checks, QR login, CAPTCHA, slider verification, or phone verification. Do not fight these checks. Stop and clearly ask the user to complete the verification in the visible browser, then continue only after the page is usable.");
                lines.add("On Boss直聘, prefer opening matching job detail pages from search results, verify company and role, click apply only when it is clearly an application intent, and stop before final submission unless auto-submit is explicitly enabled.");
            }
        } else if (!isBlank(request.companyName())) {
            lines.add("If no starting page is provided for a company hint, find the official careers or recruitment page before applying.");
        } else {
            lines.add("No required company or website was provided. Start by discovering matching companies and openings yourself.");
        }
        lines.add("Only apply to roles that clearly match the user's goal. Skip unrelated, suspicious, paid-training, commission-only, or misleading roles.");

        if (uploadInfo != null) {
            lines.add("Resume file URL for upload_file actions: " + uploadInfo.presignedUrl());
            if (!isBlank(uploadInfo.s3Uri())) {
                lines.add("Resume S3 URI: " + uploadInfo.s3Uri());
            }
            lines.add("When a page asks for a resume, CV, attachment, or file upload, use the resume file URL above as the file_url.");
        } else if (!isBlank(resumePath)) {
            lines.add("Resume local path: " + resumePath.trim());
            lines.add("If local file upload is available, use that path for resume/CV upload.");
        } else {
            lines.add("If a resume is required and no uploadable resume is available on the site, stop and report that a resume file is needed.");
        }

        String applicantProfile = applicantProfileService.promptText();
        if (!isBlank(applicantProfile)) {
            lines.add("Applicant profile saved locally. Use these details only for ordinary job application fields when the website asks for them:");
            lines.add(applicantProfile);
            lines.add("Autofill mapping guidance: use 姓名/fullName for name fields; 邮箱/email for email fields; 手机/phone for mobile or phone fields; 所在城市/currentCity for current city or address city; 学校/school, 专业/major, 学历/degree, 毕业时间/graduationDate for education fields; 作品集/portfolio for website/portfolio fields; 技能摘要/skills for skills or summary fields.");
            lines.add("If the form asks for resume text, candidate summary, personal profile, why you are suitable, or self introduction, draft a concise truthful answer from 技能摘要, 求职方向, 自我介绍, and 通用申请说明.");
            lines.add("Before leaving a field blank, check whether the same information is present in the saved applicant profile above or in the uploaded resume.");
            lines.add("For screening questions, availability questions, expected salary fields, preferred location fields, self-introduction fields, or cover-letter style questions, use the relevant saved applicant details when available.");
            lines.add("Do not invent missing applicant details. If a required profile field is missing, stop and report what the user needs to fill.");
        } else {
            lines.add("No applicant profile details are configured. If the form asks for contact, education, or profile details that are not visible in the resume, stop and report what is missing.");
        }

        if (!autoSubmit) {
            lines.add("Fill forms and prepare the application, but stop before the final submit button and report what is ready.");
            lines.add("Do not click any button that directly submits the application. If the visible apply button is the final submit action, stop before clicking it.");
            lines.add("When you stop for the user to confirm, set application status to ready_for_user_confirmation or waiting_for_user_confirmation. Do not call it submitted.");
        } else {
            lines.add("Submit the application when the role matches and the application is complete.");
        }

        lines.add("Do not enter passwords, payment information, government IDs, or sensitive information unless it is already visible on the website and clearly required for a job application.");
        lines.add("If login, CAPTCHA, phone verification, email verification, or human confirmation is required, stop and report the blocker clearly.");
        lines.add("Return structured output with each attempted role, company, status, URL, and any blocker or next step.");
        if (!isBlank(request.extraNotes())) {
            lines.add("Additional user notes: " + request.extraNotes().trim());
        }
        return String.join("\n", lines);
    }

    private List<String> companyHints(SkyvernApplyRequest request) {
        List<String> hints = new ArrayList<>();
        if (request.companyNames() != null) {
            for (String company : request.companyNames()) {
                if (!isBlank(company)) {
                    String trimmed = company.trim();
                    if (!hints.contains(trimmed)) {
                        hints.add(trimmed);
                    }
                    if (hints.size() >= 120) {
                        break;
                    }
                }
            }
        }
        return hints;
    }

    private Map<String, Object> applicationOutputSchema() {
        Map<String, Object> role = new LinkedHashMap<>();
        role.put("type", "object");
        role.put("properties", Map.of(
                "company", Map.of("type", "string"),
                "job_title", Map.of("type", "string"),
                "location", Map.of("type", "string"),
                "site", Map.of("type", "string"),
                "detail_url", Map.of("type", "string"),
                "status", Map.of("type", "string"),
                "reason", Map.of("type", "string")
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "summary", Map.of("type", "string"),
                "applications", Map.of("type", "array", "items", role),
                "blocked_by_human_verification", Map.of("type", "boolean"),
                "next_action", Map.of("type", "string")
        ));
        return schema;
    }

    private String buildTitle(SkyvernApplyRequest request) {
        if (!isBlank(request.companyName())) {
            return "Job application - " + request.companyName().trim();
        }
        String goal = request.goal().trim();
        return goal.length() > 48 ? goal.substring(0, 48) : goal;
    }

    private String resolveBaseUrl(String requestBaseUrl) {
        String fallback = System.getenv().getOrDefault("SKYVERN_BASE_URL", DEFAULT_SKYVERN_BASE_URL);
        String value = nonBlank(requestBaseUrl, configuredValue("SKYVERN_BASE_URL", fallback));
        return trimTrailingSlash(value);
    }

    private String resolveApiKey(String requestApiKey) {
        String fallback = System.getenv().getOrDefault("SKYVERN_API_KEY", "");
        return nonBlank(requestApiKey, configuredValue("SKYVERN_API_KEY", fallback));
    }

    private String resolveResumePath(String requestResumePath) {
        String fallback = System.getenv().getOrDefault("RESUME_PATH", "");
        return nonBlank(requestResumePath, configuredValue("RESUME_PATH", fallback));
    }

    private String configuredValue(String key, String fallback) {
        try {
            String value = configService.getConfigValue(key);
            return isBlank(value) ? fallback : value.trim();
        } catch (Exception e) {
            return fallback;
        }
    }

    private String normalizeUrl(String url) {
        if (isBlank(url)) {
            return "";
        }
        String trimmed = url.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        return "https://" + trimmed;
    }

    private boolean isBossUrl(String url) {
        if (isBlank(url)) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.contains("zhipin.com") || lower.contains("bosszhipin.com");
    }

    private String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isBlank() ? DEFAULT_SKYVERN_BASE_URL : trimmed;
    }

    private String sanitizeFileName(String fileName) {
        return fileName.replace("\\", "_").replace("/", "_").replace("\"", "_");
    }

    private String nonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private boolean parseBoolean(String value, boolean fallback) {
        if (isBlank(value)) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase();
        return switch (normalized) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> fallback;
        };
    }

    private String valueOf(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String providerDefaultModel(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase();
        return switch (normalized) {
            case "mimo" -> "mimo-v2.5-pro";
            case "deepseek" -> "deepseek-chat";
            case "openai" -> "gpt-4o";
            default -> "";
        };
    }

    private String providerDefaultBaseUrl(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase();
        return switch (normalized) {
            case "mimo" -> "http://127.0.0.1:8002/v1";
            case "deepseek" -> "https://api.deepseek.com/v1";
            case "openai" -> "https://api.openai.com/v1";
            default -> DEFAULT_LLM_BASE_URL;
        };
    }

    private Path writeSkyvernEnv(String provider, String modelName, String baseUrl, String apiKey, boolean supportsVision, int maxTokens) {
        try {
            Path skyvernDir = Path.of("runtime", "skyvern").toAbsolutePath().normalize();
            Path dataDir = skyvernDir.resolve("data");
            Path skyvernProfileDir = prepareBrowserProfile(Path.of("runtime", "browser-profiles", "skyvern"));
            Path skyvernSessionDir = skyvernDir.resolve("browser_sessions").toAbsolutePath().normalize();
            Files.createDirectories(dataDir);
            Files.createDirectories(skyvernSessionDir);

            String databasePath = dataDir.resolve("skyvern.db").toAbsolutePath().normalize().toString().replace("\\", "/");
            String normalizedProvider = provider == null ? DEFAULT_LLM_PROVIDER : provider.trim().toLowerCase();
            List<String> lines = new ArrayList<>();
            lines.add("ENV=local");
            lines.add("");
            lines.add("# Browser config");
            lines.add("BROWSER_TYPE=chromium-headful");
            lines.add("BROWSER_STREAMING_MODE=cdp");
            lines.add("BROWSER_ACTION_TIMEOUT_MS=10000");
            lines.add(envLine("DEFAULT_BROWSER_PROFILE_DIR", skyvernProfileDir.toString().replace("\\", "/")));
            lines.add(envLine("BROWSER_SESSION_BASE_PATH", skyvernSessionDir.toString().replace("\\", "/")));
            lines.add(envLine("BROWSER_ADDITIONAL_ARGS", "[\"--no-first-run\",\"--no-default-browser-check\",\"--disable-features=Translate\",\"--disable-popup-blocking\",\"--password-store=basic\",\"--use-mock-keychain\"]"));
            lines.add("");
            lines.add("# User-selected OpenAI-compatible model");
            lines.add(envLine("GETJOBS_LLM_PROVIDER", normalizedProvider));
            lines.add("ENABLE_OPENAI_COMPATIBLE=true");
            lines.add("OPENAI_COMPATIBLE_MODEL_KEY=OPENAI_COMPATIBLE");
            lines.add(envLine("OPENAI_COMPATIBLE_MODEL_NAME", modelName));
            lines.add(envLine("OPENAI_COMPATIBLE_API_KEY", apiKey));
            lines.add(envLine("OPENAI_COMPATIBLE_API_BASE", baseUrl));
            lines.add(envLine("OPENAI_COMPATIBLE_SUPPORTS_VISION", String.valueOf(supportsVision)));
            lines.add(envLine("OPENAI_COMPATIBLE_MAX_TOKENS", String.valueOf(maxTokens)));
            lines.add(envLine("MIMO_API_KEY", "mimo".equals(normalizedProvider) ? apiKey : ""));
            lines.add("");
            lines.add("LLM_KEY=OPENAI_COMPATIBLE");
            lines.add("SECONDARY_LLM_KEY=OPENAI_COMPATIBLE");
            lines.add("MAX_STEPS_PER_RUN=" + PRACTICAL_UNLIMITED_STEPS);
            lines.add("MAX_STEPS_PER_TASK_V2=" + PRACTICAL_UNLIMITED_STEPS);
            lines.add("");
            lines.add("DATABASE_STRING=sqlite+aiosqlite:///" + databasePath);
            lines.add("LOG_LEVEL=INFO");
            lines.add("PORT=8001");
            lines.add("SKYVERN_TELEMETRY=false");
            lines.add(envLine("SKYVERN_API_KEY", configuredValue("SKYVERN_API_KEY", "")));

            Path envPath = skyvernEnvPath();
            Files.writeString(envPath, String.join(System.lineSeparator(), lines) + System.lineSeparator(), StandardCharsets.UTF_8);
            return envPath;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write Skyvern model config: " + e.getMessage(), e);
        }
    }

    private Path skyvernEnvPath() {
        return Path.of("runtime", "skyvern", ".env").toAbsolutePath().normalize();
    }

    private String envLine(String key, String value) {
        return key + "=" + quoteEnv(value == null ? "" : value);
    }

    private String quoteEnv(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private Path prepareBrowserProfile(Path relativeProfileDir) throws IOException {
        Path profileDir = relativeProfileDir.toAbsolutePath().normalize();
        if (isBrowserProfileDamaged(profileDir)) {
            Path backup = profileDir.resolveSibling(profileDir.getFileName() + "-bak-" +
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            Files.move(profileDir, backup);
            log.warn("Skyvern browser profile looked damaged and was backed up to {}. User may need to log in again.", backup);
        }
        Files.createDirectories(profileDir);
        return profileDir;
    }

    private boolean isBrowserProfileDamaged(Path profileDir) {
        if (!Files.exists(profileDir)) {
            return false;
        }
        return !isValidJsonIfExists(profileDir.resolve("Local State"))
                || !isValidJsonIfExists(profileDir.resolve("Default").resolve("Preferences"))
                || !isValidJsonIfExists(profileDir.resolve("Preferences"));
    }

    private boolean isValidJsonIfExists(Path path) {
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return true;
        }
        try {
            OBJECT_MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            log.warn("Invalid browser profile JSON: {} ({})", path, e.getMessage());
            return false;
        }
    }

    private void attachRunViewingLinks(Map<String, Object> runData, String baseUrl, String runId) {
        if (runData == null || isBlank(runId)) {
            return;
        }
        runData.putIfAbsent("runStatusUrl", "/api/skyvern/runs/" + runId);
        runData.putIfAbsent("skyvernOpenApiUrl", baseUrl + "/openapi.json");
        runData.putIfAbsent("browserDebugUrl", "http://127.0.0.1:9222/json");
        runData.putIfAbsent("browserHint", "Skyvern 使用独立的可见 Chromium。如果没有自动弹出窗口，请通过任务详情、截图或浏览器调试入口查看进度。");
    }

    private record UploadInfo(String s3Uri, String presignedUrl) {
    }

    public boolean hasActiveRuns() {
        return !activeRunIds.isEmpty();
    }

    private void cleanFinishedRun(String runId, Map<String, Object> runData) {
        String status = valueOf(runData.get("status"));
        if (isBlank(status)) return;
        String normalized = status.trim().toLowerCase();
        if (normalized.equals("completed") || normalized.equals("failed")
                || normalized.equals("cancelled") || normalized.equals("canceled")
                || normalized.equals("terminated") || normalized.equals("timed_out")) {
            activeRunIds.remove(runId.trim());
            tryPendingSkyvernRestart();
        }
    }

    private void tryPendingSkyvernRestart() {
        if (!pendingSkyvernRestart || hasActiveRuns()) return;
        pendingSkyvernRestart = false;
        try {
            processManager.restartIfNeeded();
            log.info("Skyvern restarted after pending config change");
        } catch (Exception e) {
            log.warn("Failed to restart Skyvern after pending config change: {}", e.getMessage());
        }
    }

}
