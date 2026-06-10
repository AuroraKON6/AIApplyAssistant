package com.getjobs.application.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@Slf4j
@Service
public class ResumeTextExtractionService {
    private static final int MAX_TEXT_CHARS = 24_000;

    public String extractText(Path resumePath) {
        if (resumePath == null) {
            throw new IllegalArgumentException("简历路径不能为空。");
        }
        Path path = resumePath.toAbsolutePath().normalize();
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("简历文件不存在: " + path);
        }

        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            String text;
            if (fileName.endsWith(".pdf")) {
                text = extractPdf(path);
            } else if (fileName.endsWith(".docx")) {
                text = extractDocx(path);
            } else if (fileName.endsWith(".doc")) {
                text = extractDoc(path);
            } else {
                throw new IllegalArgumentException("只支持 PDF、DOC、DOCX 简历文件。");
            }
            text = normalizeText(text);
            if (text.isBlank()) {
                throw new IllegalArgumentException("没有从简历中读取到文字，可能是扫描图片版简历。");
            }
            return text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text;
        } catch (IOException e) {
            throw new RuntimeException("读取简历失败: " + e.getMessage(), e);
        }
    }

    private String extractPdf(Path path) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private String extractDocx(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path);
             XWPFDocument document = new XWPFDocument(input);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String extractDoc(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path);
             HWPFDocument document = new HWPFDocument(input);
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u0000', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
