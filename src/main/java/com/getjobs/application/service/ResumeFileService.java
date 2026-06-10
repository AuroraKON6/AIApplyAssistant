package com.getjobs.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ResumeFileService {
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "doc", "docx");
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    private final ConfigService configService;
    private final ApplicantProfileService applicantProfileService;

    public Map<String, Object> saveResume(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择简历文件。");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("简历文件不能超过 20MB。");
        }

        String originalName = file.getOriginalFilename() == null ? "resume" : file.getOriginalFilename();
        String extension = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("只支持 PDF、DOC、DOCX 简历文件。");
        }

        try {
            Path resumeDir = Path.of("runtime", "resumes").toAbsolutePath().normalize();
            Files.createDirectories(resumeDir);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String fileName = timestamp + "-" + UUID.randomUUID().toString().substring(0, 8) + "-" + sanitize(originalName);
            if (!fileName.toLowerCase(Locale.ROOT).endsWith("." + extension)) {
                fileName += "." + extension;
            }

            Path target = resumeDir.resolve(fileName).normalize();
            if (!target.startsWith(resumeDir)) {
                throw new IllegalArgumentException("简历文件名无效。");
            }
            file.transferTo(target);

            String absolutePath = target.toString();
            configService.upsertConfig("RESUME_PATH", absolutePath, "ai", "本地附件简历路径");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("fileName", originalName);
            result.put("savedPath", absolutePath);
            result.put("size", file.getSize());
            try {
                Map<String, Object> profileResult = applicantProfileService.extractAndSaveFromResume(target);
                result.put("profile", profileResult.get("profile"));
                result.put("profileExtracted", true);
                result.put("profileMessage", profileResult.get("message"));
                if (profileResult.containsKey("warning")) {
                    result.put("profileWarning", profileResult.get("warning"));
                }
                result.put("message", "简历已保存，并已尝试自动识别投递资料。");
            } catch (Exception e) {
                result.put("profileExtracted", false);
                result.put("profileWarning", e.getMessage());
                result.put("message", "简历已保存，投递时会自动上传；但投递资料未自动识别。");
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException("保存简历失败: " + e.getMessage(), e);
        }
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String sanitize(String fileName) {
        String sanitized = fileName.replace("\\", "_")
                .replace("/", "_")
                .replace(":", "_")
                .replace("*", "_")
                .replace("?", "_")
                .replace("\"", "_")
                .replace("<", "_")
                .replace(">", "_")
                .replace("|", "_")
                .trim();
        return sanitized.isBlank() ? "resume.pdf" : sanitized;
    }
}
