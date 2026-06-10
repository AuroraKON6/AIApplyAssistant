package com.getjobs.application.service;

import com.getjobs.worker.manager.PlaywrightManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosticsService {

    private final ConfigService configService;
    private final SkyvernService skyvernService;
    private final WebSearchService webSearchService;
    private final PlaywrightManager playwrightManager;
    private final ApplicantProfileService applicantProfileService;

    public List<Map<String, Object>> runDiagnostics() {
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(checkAiConfig());
        checks.add(checkLlmApi());
        checks.add(checkSkyvern());
        checks.add(checkPlaywright());
        checks.add(checkResumeFile());
        checks.add(checkWebSearch());
        return checks;
    }

    public Map<String, Object> runApplicationReadiness() {
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(readinessAiConfig());
        checks.add(readinessSkyvern());
        checks.add(readinessResumeFile());
        checks.add(readinessApplicantProfile());
        checks.add(readinessApplicationAnswers());

        boolean ready = checks.stream().noneMatch(item -> "error".equals(item.get("status")));
        long warnings = checks.stream().filter(item -> "warning".equals(item.get("status"))).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ready", ready);
        result.put("warningCount", warnings);
        result.put("items", checks);
        result.put("message", ready
                ? (warnings > 0 ? "可以开始投递，但有资料建议补充。" : "投递资料已准备好。")
                : "请先补齐红色项目，再开始投递。");
        return result;
    }

    private Map<String, Object> readinessAiConfig() {
        String baseUrl = configService.getConfigValue("LLM_BASE_URL");
        String apiKey = configService.getConfigValue("LLM_API_KEY");
        String modelName = configService.getConfigValue("LLM_MODEL_NAME");

        List<String> missing = new ArrayList<>();
        if (isBlank(baseUrl)) missing.add("API 地址");
        if (isBlank(apiKey)) missing.add("API Key");
        if (isBlank(modelName)) missing.add("模型名称");

        if (missing.isEmpty()) {
            return readinessItem("AI 配置", "ok", "已保存模型配置，会用于识别简历和填写表单。");
        }
        return readinessItem("AI 配置", "error", "缺少 " + String.join("、", missing) + "，请先保存并应用。");
    }

    private Map<String, Object> readinessSkyvern() {
        try {
            Map<String, Object> health = skyvernService.health(null, null);
            if (Boolean.TRUE.equals(health.get("reachable"))) {
                return readinessItem("浏览器执行服务", "ok", "Skyvern 已可用，可以打开网页并填写表单。");
            }
            Object error = health.get("error");
            return readinessItem("浏览器执行服务", "error", "Skyvern 不可用" + (error != null ? ": " + error : "。"));
        } catch (Exception e) {
            return readinessItem("浏览器执行服务", "error", "Skyvern 检查失败: " + userMessage(e));
        }
    }

    private Map<String, Object> readinessResumeFile() {
        String resumePath = configService.getConfigValue("RESUME_PATH");
        if (isBlank(resumePath)) {
            return readinessItem("附件简历", "error", "还没有上传简历，投递时无法自动上传附件。");
        }
        Path path = Path.of(resumePath.trim()).toAbsolutePath().normalize();
        if (Files.exists(path) && Files.isRegularFile(path)) {
            return readinessItem("附件简历", "ok", "已准备好: " + path.getFileName());
        }
        return readinessItem("附件简历", "error", "简历文件不存在，请重新上传。");
    }

    private Map<String, Object> readinessApplicantProfile() {
        Map<String, Object> profile = applicantProfileService.getProfile();
        List<String> missing = new ArrayList<>();
        if (isBlank(valueOf(profile.get("fullName")))) missing.add("姓名");
        if (isBlank(valueOf(profile.get("email")))) missing.add("邮箱");
        if (isBlank(valueOf(profile.get("phone")))) missing.add("手机");

        int filled = filledCount(profile, List.of(
                "fullName", "email", "phone", "currentCity", "school", "major",
                "degree", "graduationDate", "wechat", "portfolio", "expectedRole", "skills"
        ));
        if (missing.isEmpty()) {
            return readinessItem("基础投递资料", "ok", "姓名、邮箱、手机已准备好，共有 " + filled + " 项基础资料可用于填表。");
        }
        return readinessItem("基础投递资料", "error", "缺少 " + String.join("、", missing) + "，请从简历识别或手动填写。");
    }

    private Map<String, Object> readinessApplicationAnswers() {
        Map<String, Object> profile = applicantProfileService.getProfile();
        int filled = filledCount(profile, List.of(
                "availability", "internshipDuration", "weeklyAvailability", "expectedSalary",
                "preferredLocations", "workPreference", "selfIntroduction", "coverLetter"
        ));
        if (filled >= 2) {
            return readinessItem("常用申请回答", "ok", "已准备 " + filled + " 项，遇到开放题时会优先使用。");
        }
        return readinessItem("常用申请回答", "warning", "建议补充到岗时间、实习周期、每周可实习、自我介绍等，官网表单经常会问。");
    }

    private Map<String, Object> readinessItem(String name, String status, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("status", status);
        item.put("message", message);
        return item;
    }

    private int filledCount(Map<String, Object> values, List<String> keys) {
        int count = 0;
        for (String key : keys) {
            if (!isBlank(valueOf(values.get(key)))) {
                count++;
            }
        }
        return count;
    }

    private Map<String, Object> checkAiConfig() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", "AI 模型配置");
        String baseUrl = configService.getConfigValue("LLM_BASE_URL");
        String apiKey = configService.getConfigValue("LLM_API_KEY");
        String modelName = configService.getConfigValue("LLM_MODEL_NAME");

        List<String> missing = new ArrayList<>();
        if (isBlank(baseUrl)) missing.add("API 地址");
        if (isBlank(apiKey)) missing.add("API Key");
        if (isBlank(modelName)) missing.add("模型名称");

        if (missing.isEmpty()) {
            item.put("status", "ok");
            item.put("message", "模型: " + modelName);
        } else {
            item.put("status", "warning");
            item.put("message", "缺少: " + String.join("、", missing) + "，请在下方填写并点击\"保存并应用\"");
        }
        return item;
    }

    private Map<String, Object> checkLlmApi() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", "AI 模型连接");

        String baseUrl = configService.getConfigValue("LLM_BASE_URL");
        String apiKey = configService.getConfigValue("LLM_API_KEY");
        String modelName = configService.getConfigValue("LLM_MODEL_NAME");

        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(modelName)) {
            item.put("status", "warning");
            item.put("message", "请先完成 AI 模型配置");
            return item;
        }

        try {
            String url = trimTrailingSlash(baseUrl) + "/models";
            BlockingHttpClient.Response response = BlockingHttpClient.get(
                    url,
                    Map.of("Authorization", "Bearer " + apiKey),
                    8
            );
            if (response.statusCode() >= 200 && response.statusCode() < 400) {
                item.put("status", "ok");
                item.put("message", "API 可达，模型: " + modelName);
            } else {
                item.put("status", "error");
                item.put("message", "API 返回 HTTP " + response.statusCode() + "，请检查 API 地址和 Key 是否正确");
            }
        } catch (Exception e) {
            item.put("status", "error");
            item.put("message", "无法连接到 API: " + userMessage(e));
        }
        return item;
    }

    private Map<String, Object> checkSkyvern() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", "浏览器执行服务 (Skyvern)");

        try {
            Map<String, Object> health = skyvernService.health(null, null);
            Object reachable = health.get("reachable");
            if (Boolean.TRUE.equals(reachable)) {
                item.put("status", "ok");
                item.put("message", "Skyvern 运行正常 (端口 8001)");
            } else {
                item.put("status", "error");
                Object error = health.get("error");
                item.put("message", "Skyvern 不可达" + (error != null ? ": " + error : "，请检查服务是否启动"));
            }
        } catch (Exception e) {
            item.put("status", "error");
            item.put("message", "Skyvern 检查失败: " + userMessage(e));
        }
        return item;
    }

    private Map<String, Object> checkPlaywright() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", "Playwright 浏览器引擎");

        try {
            boolean initialized = playwrightManager.isInitialized();
            if (initialized) {
                item.put("status", "ok");
                item.put("message", "Chromium 已启动 (调试端口 " + playwrightManager.getCdpPort() + ")");
            } else {
                item.put("status", "warning");
                item.put("message", "浏览器引擎未初始化，首次投递时会自动启动");
            }
        } catch (Exception e) {
            item.put("status", "error");
            item.put("message", "Playwright 检查失败: " + userMessage(e));
        }
        return item;
    }

    private Map<String, Object> checkResumeFile() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", "简历文件");

        String resumePath = configService.getConfigValue("RESUME_PATH");
        if (isBlank(resumePath)) {
            item.put("status", "warning");
            item.put("message", "未配置简历路径，投递时无法自动上传简历");
            return item;
        }

        Path path = Path.of(resumePath.trim()).toAbsolutePath().normalize();
        if (Files.exists(path) && Files.isRegularFile(path)) {
            long sizeKb = 0;
            try {
                sizeKb = Files.size(path) / 1024;
            } catch (Exception ignored) {}
            item.put("status", "ok");
            item.put("message", path.getFileName() + " (" + sizeKb + " KB)");
        } else {
            item.put("status", "error");
            item.put("message", "文件不存在: " + path);
        }
        return item;
    }

    private Map<String, Object> checkWebSearch() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", "网页搜索");

        try {
            List<WebSearchService.SearchResult> results = webSearchService.search("test", 1);
            if (!results.isEmpty()) {
                item.put("status", "ok");
                item.put("message", "搜索引擎可用");
            } else {
                item.put("status", "warning");
                item.put("message", "搜索引擎未返回结果，可能是网络问题，岗位发现功能可能受限");
            }
        } catch (Exception e) {
            item.put("status", "error");
            item.put("message", "搜索失败: " + userMessage(e));
        }
        return item;
    }

    private String userMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return "未知错误";
        if (msg.length() > 120) return msg.substring(0, 120) + "...";
        return msg;
    }

    private String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String valueOf(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}
