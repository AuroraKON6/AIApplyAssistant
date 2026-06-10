package com.getjobs.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class ApplicationHistoryService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_RECORDS = 1000;
    private static final List<String> ALLOWED_FIELDS = List.of(
            "id", "company", "title", "url", "source", "status", "runId",
            "skyvernStatus", "message", "error", "appUrl", "runStatusUrl",
            "browserDebugUrl", "autoSubmit", "checkedAt", "targetCompany",
            "officialWebsite", "careersPage", "companySearchStatus"
    );

    private final Path historyPath = Path.of("runtime", "applications", "history.json")
            .toAbsolutePath()
            .normalize();

    public synchronized List<Map<String, Object>> list(int limit) {
        List<Map<String, Object>> records = readRecords();
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 100 : limit, MAX_RECORDS));
        return records.stream().limit(safeLimit).toList();
    }

    public synchronized Map<String, Object> upsert(Map<String, Object> request) {
        if (request == null) {
            throw new IllegalArgumentException("投递记录不能为空。");
        }

        List<Map<String, Object>> records = readRecords();
        String urlKey = normalizeUrl(valueOf(request.get("url")));
        String runId = valueOf(request.get("runId"));
        int existingIndex = findExisting(records, urlKey, runId);

        Map<String, Object> record = existingIndex >= 0
                ? new LinkedHashMap<>(records.remove(existingIndex))
                : new LinkedHashMap<>();

        if (isBlank(valueOf(record.get("id")))) {
            record.put("id", UUID.randomUUID().toString().substring(0, 12));
        }
        if (isBlank(valueOf(record.get("createdAt")))) {
            record.put("createdAt", nowText());
        }

        for (String field : ALLOWED_FIELDS) {
            if (request.containsKey(field)) {
                Object value = request.get(field);
                if (value instanceof String text) {
                    value = sanitize(text, field);
                }
                record.put(field, value == null ? "" : value);
            }
        }
        if (isBlank(valueOf(record.get("status")))) {
            record.put("status", "running");
        }
        record.put("urlKey", urlKey);
        record.put("updatedAt", nowText());

        records.add(0, record);
        while (records.size() > MAX_RECORDS) {
            records.remove(records.size() - 1);
        }
        writeRecords(records);
        return record;
    }

    public synchronized Map<String, Object> check(List<String> urls) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (urls == null || urls.isEmpty()) {
            return result;
        }

        List<Map<String, Object>> records = readRecords();
        Map<String, Map<String, Object>> byUrl = new LinkedHashMap<>();
        for (Map<String, Object> record : records) {
            String key = normalizeUrl(valueOf(record.get("url")));
            if (!isBlank(key)) {
                byUrl.putIfAbsent(key, record);
            }
        }

        for (String url : urls) {
            String key = normalizeUrl(url);
            if (!isBlank(key) && byUrl.containsKey(key)) {
                result.put(url, byUrl.get(key));
            }
        }
        return result;
    }

    public synchronized Map<String, Object> delete(String idOrUrl) {
        if (isBlank(idOrUrl)) {
            throw new IllegalArgumentException("投递记录 ID 或链接不能为空。");
        }
        String normalized = normalizeUrl(idOrUrl);
        List<Map<String, Object>> records = readRecords();
        int before = records.size();
        records.removeIf(record ->
                idOrUrl.equals(valueOf(record.get("id")))
                        || normalized.equals(normalizeUrl(valueOf(record.get("url"))))
        );
        writeRecords(records);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", before - records.size());
        return result;
    }

    private int findExisting(List<Map<String, Object>> records, String urlKey, String runId) {
        for (int i = 0; i < records.size(); i++) {
            Map<String, Object> record = records.get(i);
            String recordUrlKey = normalizeUrl(valueOf(record.get("url")));
            if (!isBlank(urlKey) && urlKey.equals(recordUrlKey)) {
                return i;
            }
            if (!isBlank(runId) && runId.equals(valueOf(record.get("runId")))) {
                return i;
            }
        }
        return -1;
    }

    private List<Map<String, Object>> readRecords() {
        if (!Files.exists(historyPath)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(historyPath, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return new ArrayList<>();
            }
            List<Map<String, Object>> parsed = OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
            return new ArrayList<>(parsed);
        } catch (Exception e) {
            log.warn("Failed to read application history: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void writeRecords(List<Map<String, Object>> records) {
        try {
            Files.createDirectories(historyPath.getParent());
            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(records);
            Files.writeString(historyPath, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("保存投递记录失败: " + e.getMessage(), e);
        }
    }

    private String normalizeUrl(String value) {
        if (isBlank(value)) {
            return "";
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        while (trimmed.endsWith("/") || trimmed.endsWith("#")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String sanitize(String value, String field) {
        int max = "message".equals(field) || "error".equals(field) ? 800 : 300;
        String sanitized = value.replace('\u0000', ' ')
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        return sanitized.length() > max ? sanitized.substring(0, max) : sanitized;
    }

    private String nowText() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private String valueOf(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
