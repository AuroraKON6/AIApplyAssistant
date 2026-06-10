package com.getjobs.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicantProfileService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<ProfileField> FIELDS = List.of(
            new ProfileField("fullName", "APPLICANT_FULL_NAME", "姓名"),
            new ProfileField("email", "APPLICANT_EMAIL", "邮箱"),
            new ProfileField("phone", "APPLICANT_PHONE", "手机"),
            new ProfileField("currentCity", "APPLICANT_CURRENT_CITY", "所在城市"),
            new ProfileField("school", "APPLICANT_SCHOOL", "学校"),
            new ProfileField("major", "APPLICANT_MAJOR", "专业"),
            new ProfileField("degree", "APPLICANT_DEGREE", "学历"),
            new ProfileField("graduationDate", "APPLICANT_GRADUATION_DATE", "毕业时间"),
            new ProfileField("wechat", "APPLICANT_WECHAT", "微信/备用联系方式"),
            new ProfileField("portfolio", "APPLICANT_PORTFOLIO", "作品集/个人主页"),
            new ProfileField("expectedRole", "APPLICANT_EXPECTED_ROLE", "求职方向"),
            new ProfileField("skills", "APPLICANT_SKILLS", "技能摘要"),
            new ProfileField("availability", "APPLICANT_AVAILABILITY", "到岗时间"),
            new ProfileField("internshipDuration", "APPLICANT_INTERNSHIP_DURATION", "实习周期"),
            new ProfileField("weeklyAvailability", "APPLICANT_WEEKLY_AVAILABILITY", "每周可实习"),
            new ProfileField("expectedSalary", "APPLICANT_EXPECTED_SALARY", "期望薪资"),
            new ProfileField("preferredLocations", "APPLICANT_PREFERRED_LOCATIONS", "期望城市"),
            new ProfileField("workPreference", "APPLICANT_WORK_PREFERENCE", "工作方式偏好"),
            new ProfileField("selfIntroduction", "APPLICANT_SELF_INTRODUCTION", "自我介绍"),
            new ProfileField("coverLetter", "APPLICANT_COVER_LETTER", "通用申请说明")
    );

    private final ConfigService configService;
    private final ResumeTextExtractionService resumeTextExtractionService;

    public Map<String, Object> getProfile() {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean configured = false;
        for (ProfileField field : FIELDS) {
            String value = configuredValue(field.configKey(), "");
            result.put(field.name(), value);
            if (!isBlank(value)) {
                configured = true;
            }
        }
        result.put("configured", configured);
        return result;
    }

    public Map<String, Object> saveProfile(Map<String, Object> request) {
        if (request == null) {
            throw new IllegalArgumentException("投递资料不能为空。");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (ProfileField field : FIELDS) {
            normalized.put(field.name(), normalizeProfileValue(valueOf(request.get(field.name())), field.name()));
        }
        persistProfile(normalized);
        Map<String, Object> result = getProfile();
        result.put("message", "投递资料已保存。");
        return result;
    }

    public Map<String, Object> extractAndSaveFromResume(Path resumePath) {
        String text = resumeTextExtractionService.extractText(resumePath);
        Map<String, String> existing = profileValues();
        Map<String, String> detected = heuristicProfile(text);
        List<String> warnings = new ArrayList<>();

        try {
            Map<String, String> llmProfile = extractWithLlm(text);
            mergeDetected(detected, llmProfile);
        } catch (IllegalStateException e) {
            warnings.add(e.getMessage());
        } catch (Exception e) {
            warnings.add("AI 自动识别失败，已保留可直接识别的信息。");
            log.warn("Resume profile LLM extraction failed: {}", e.getMessage(), e);
        }

        Map<String, String> merged = new LinkedHashMap<>(existing);
        List<String> changedFields = new ArrayList<>();
        for (ProfileField field : FIELDS) {
            String value = normalizeProfileValue(detected.get(field.name()), field.name());
            if (!isBlank(value)) {
                merged.put(field.name(), value);
                changedFields.add(field.name());
            }
        }

        if (!changedFields.isEmpty()) {
            persistProfile(merged);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile", getProfile());
        result.put("extractedFields", changedFields);
        result.put("textChars", text.length());
        result.put("message", changedFields.isEmpty()
                ? "简历已读取，但没有自动识别到可填写资料。"
                : "已根据简历自动填入 " + changedFields.size() + " 项投递资料。");
        if (!warnings.isEmpty()) {
            result.put("warning", String.join("；", warnings));
        }
        return result;
    }

    public String promptText() {
        Map<String, String> values = profileValues();
        List<String> lines = new ArrayList<>();
        for (ProfileField field : FIELDS) {
            String value = values.get(field.name());
            if (!isBlank(value)) {
                lines.add(field.label() + ": " + value);
            }
        }
        return String.join("\n", lines);
    }

    private Map<String, String> extractWithLlm(String resumeText) throws Exception {
        String baseUrl = configuredValue("LLM_BASE_URL", "");
        String apiKey = configuredValue("LLM_API_KEY", "");
        String modelName = configuredValue("LLM_MODEL_NAME", "");
        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(modelName)) {
            throw new IllegalStateException("AI 配置不完整，已跳过简历智能识别。");
        }

        JSONObject body = new JSONObject();
        body.put("model", modelName);
        body.put("temperature", 0.1);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject(Map.of(
                "role", "system",
                "content", "You extract applicant profile fields from resume text. Return only valid JSON. Do not invent missing fields."
        )));
        messages.put(new JSONObject(Map.of(
                "role", "user",
                "content", buildExtractionPrompt(resumeText)
        )));
        body.put("messages", messages);

        BlockingHttpClient.Response response = BlockingHttpClient.postJson(
                trimTrailingSlash(baseUrl) + "/chat/completions",
                Map.of("Accept", "application/json", "Authorization", "Bearer " + apiKey),
                body.toString(),
                90
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("AI 返回 HTTP " + response.statusCode());
        }

        Map<String, Object> parsed = OBJECT_MAPPER.readValue(response.body(), new TypeReference<>() {});
        Object choices = parsed.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> first)) {
            return Map.of();
        }
        Object message = first.get("message");
        if (!(message instanceof Map<?, ?> messageMap)) {
            return Map.of();
        }
        String content = valueOf(messageMap.get("content"));
        if (isBlank(content)) {
            return Map.of();
        }

        String json = cleanJsonResponse(content);
        Map<String, Object> profile = OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
        Map<String, String> result = new LinkedHashMap<>();
        for (ProfileField field : FIELDS) {
            result.put(field.name(), normalizeProfileValue(valueOf(profile.get(field.name())), field.name()));
        }
        return result;
    }

    private String buildExtractionPrompt(String resumeText) {
        return """
                Extract these fields from the resume text and return one JSON object with exactly these keys:
                fullName, email, phone, currentCity, school, major, degree, graduationDate, wechat, portfolio, expectedRole, skills,
                availability, internshipDuration, weeklyAvailability, expectedSalary, preferredLocations, workPreference, selfIntroduction, coverLetter.

                Rules:
                - Use empty string when a field is not clearly present.
                - Keep phone/email exactly as usable contact information.
                - Do not extract government ID numbers, passwords, bank cards, or private family information.
                - skills should be a short comma-separated summary useful for job applications.
                - degree can be 本科/硕士/博士/大专/高中/other if visible.
                - selfIntroduction and coverLetter should be short, truthful, and based only on visible resume content.
                - expectedSalary, availability, internshipDuration, weeklyAvailability, preferredLocations, and workPreference should stay empty unless visible.

                Resume text:
                """ + "\n" + resumeText;
    }

    private Map<String, String> heuristicProfile(String text) {
        Map<String, String> result = emptyProfileValues();
        String normalized = text == null ? "" : text;

        putIfPresent(result, "email", firstMatch(normalized, Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")));
        putIfPresent(result, "phone", cleanupPhone(firstMatch(normalized, Pattern.compile("(?<!\\d)(?:\\+?86[-\\s]?)?1[3-9]\\d[-\\s]?\\d{4}[-\\s]?\\d{4}(?!\\d)"))));
        putIfPresent(result, "portfolio", firstMatch(normalized, Pattern.compile("https?://[^\\s,，。)）]+")));
        putIfPresent(result, "degree", firstDegree(normalized));
        putIfPresent(result, "fullName", guessName(normalized));
        putIfPresent(result, "school", firstLineContaining(normalized, "大学", "学院", "University", "College"));
        putIfPresent(result, "major", valueAfterLabel(normalized, "专业", "Major"));
        putIfPresent(result, "graduationDate", graduationDate(normalized));
        putIfPresent(result, "currentCity", valueAfterLabel(normalized, "现居", "所在地", "所在城市", "城市"));
        putIfPresent(result, "availability", valueAfterLabel(normalized, "到岗时间", "最快到岗", "入职时间"));
        putIfPresent(result, "internshipDuration", valueAfterLabel(normalized, "实习周期", "实习时长"));
        putIfPresent(result, "weeklyAvailability", valueAfterLabel(normalized, "每周", "每周可实习", "实习天数"));
        putIfPresent(result, "expectedSalary", valueAfterLabel(normalized, "期望薪资", "期望工资", "薪资要求"));
        putIfPresent(result, "preferredLocations", valueAfterLabel(normalized, "期望城市", "意向城市", "目标城市"));
        return result;
    }

    private void mergeDetected(Map<String, String> base, Map<String, String> incoming) {
        if (incoming == null) {
            return;
        }
        for (ProfileField field : FIELDS) {
            String value = normalizeProfileValue(incoming.get(field.name()), field.name());
            if (!isBlank(value)) {
                base.put(field.name(), value);
            }
        }
    }

    private Map<String, String> profileValues() {
        Map<String, String> result = emptyProfileValues();
        for (ProfileField field : FIELDS) {
            result.put(field.name(), configuredValue(field.configKey(), ""));
        }
        return result;
    }

    private Map<String, String> emptyProfileValues() {
        Map<String, String> result = new LinkedHashMap<>();
        for (ProfileField field : FIELDS) {
            result.put(field.name(), "");
        }
        return result;
    }

    private void persistProfile(Map<String, String> values) {
        for (ProfileField field : FIELDS) {
            String value = normalizeProfileValue(values.get(field.name()), field.name());
            configService.upsertConfig(field.configKey(), value, "applicant", "本地投递资料-" + field.label());
        }
    }

    private String configuredValue(String key, String fallback) {
        try {
            String value = configService.getConfigValue(key);
            if (!isBlank(value)) {
                return value.trim();
            }
            String env = System.getenv(key);
            return isBlank(env) ? fallback : env.trim();
        } catch (Exception e) {
            return fallback;
        }
    }

    private String firstMatch(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group().trim() : "";
    }

    private String firstDegree(String text) {
        for (String degree : List.of("博士", "硕士", "研究生", "本科", "大专", "专科", "高中")) {
            if (text.contains(degree)) {
                return degree;
            }
        }
        return "";
    }

    private String guessName(String text) {
        String[] lines = text.split("\\n");
        int checked = 0;
        for (String line : lines) {
            String candidate = line.replaceAll("(?i)^(姓名|name)[:：\\s]+", "").trim();
            if (candidate.isBlank()) {
                continue;
            }
            checked++;
            String compact = candidate.replaceAll("\\s+", " ");
            if (compact.contains("简历") || compact.contains("求职") || compact.contains("电话") || compact.contains("邮箱")) {
                continue;
            }
            if (compact.matches("[\\p{IsHan}]{2,5}") || compact.matches("[A-Za-z][A-Za-z .'-]{2,38}")) {
                return compact;
            }
            if (checked >= 16) {
                break;
            }
        }
        return "";
    }

    private String firstLineContaining(String text, String... needles) {
        for (String line : text.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.length() > 80) {
                continue;
            }
            for (String needle : needles) {
                if (trimmed.toLowerCase().contains(needle.toLowerCase())) {
                    return trimmed;
                }
            }
        }
        return "";
    }

    private String valueAfterLabel(String text, String... labels) {
        for (String label : labels) {
            Pattern pattern = Pattern.compile(Pattern.quote(label) + "\\s*[:：]?\\s*([^\\n，,；;]{2,40})", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        return "";
    }

    private String graduationDate(String text) {
        String line = firstLineContaining(text, "毕业", "graduate", "graduation");
        if (isBlank(line)) {
            return "";
        }
        return firstMatch(line, Pattern.compile("20\\d{2}(?:[./\\-年]\\d{1,2})?"));
    }

    private void putIfPresent(Map<String, String> map, String key, String value) {
        if (!isBlank(value)) {
            map.put(key, normalizeProfileValue(value, key));
        }
    }

    private String cleanupPhone(String phone) {
        if (isBlank(phone)) {
            return "";
        }
        return phone.replaceAll("[\\s-]+", "");
    }

    private String normalizeProfileValue(String value, String fieldName) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\u0000', ' ')
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        int max = "selfIntroduction".equals(fieldName) || "coverLetter".equals(fieldName)
                ? 1200
                : ("skills".equals(fieldName) ? 600 : 160);
        return normalized.length() > max ? normalized.substring(0, max) : normalized;
    }

    private String cleanJsonResponse(String raw) {
        String cleaned = raw == null ? "" : raw.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            cleaned = cleaned.substring(start, end + 1);
        }
        return cleaned;
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

    private record ProfileField(String name, String configKey, String label) {
    }
}
