package com.getjobs.application.service;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CompanyListFileService {
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("xlsx", "xls", "csv");
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final int MAX_COMPANIES = 500;
    private static final int MAX_HEADER_ROWS = 15;
    private static final Pattern SPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern PURE_NUMBER_PATTERN = Pattern.compile("^\\d+(\\.0)?$");
    private static final Pattern CREDIT_CODE_PATTERN = Pattern.compile("^[0-9A-Z]{15,20}$");

    public Map<String, Object> parseCompanyList(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择公司名单文件。");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("公司名单文件不能超过 20MB。");
        }

        String originalName = file.getOriginalFilename() == null ? "companies" : file.getOriginalFilename();
        String extension = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("只支持 XLSX、XLS、CSV 公司名单。");
        }

        try {
            List<List<String>> rows = "csv".equals(extension)
                    ? readCsvRows(file.getBytes())
                    : readWorkbookRows(file.getBytes());
            ParsedCompanies parsed = extractCompanyNames(rows);
            if (parsed.companies().isEmpty()) {
                throw new IllegalArgumentException("没有识别到公司名称，请确认表格里有“公司”“企业名称”“单位名称”等列。");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("fileName", originalName);
            result.put("companies", parsed.companies());
            result.put("count", parsed.companies().size());
            result.put("columnLabel", parsed.columnLabel());
            result.put("columnIndex", parsed.columnIndex());
            result.put("headerRow", parsed.headerRow());
            result.put("message", "已从" + parsed.columnLabel() + "识别 " + parsed.companies().size()
                    + " 家公司；查找岗位时会逐家公司找官网、招聘页和相似岗位。");
            return result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析公司名单失败: " + e.getMessage(), e);
        }
    }

    private List<List<String>> readWorkbookRows(byte[] bytes) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(Locale.CHINA);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) {
                return rows;
            }
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                List<String> values = new ArrayList<>();
                short lastCell = row.getLastCellNum();
                for (int i = 0; i < Math.max(lastCell, 0); i++) {
                    Cell cell = row.getCell(i);
                    values.add(normalize(formatter.formatCellValue(cell)));
                }
                rows.add(values);
            }
        }
        return compactRows(rows);
    }

    private List<List<String>> readCsvRows(byte[] bytes) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        Charset charset = looksLikeUtf8(bytes) ? StandardCharsets.UTF_8 : Charset.forName("GBK");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), charset))) {
            String line;
            while ((line = reader.readLine()) != null) {
                rows.add(parseCsvLine(stripBom(line)).stream().map(this::normalize).toList());
            }
        }
        return compactRows(rows);
    }

    private List<List<String>> compactRows(List<List<String>> rows) {
        return rows.stream()
                .map(row -> row.stream().map(this::normalize).toList())
                .filter(row -> row.stream().anyMatch(value -> !value.isBlank()))
                .toList();
    }

    private ParsedCompanies extractCompanyNames(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return new ParsedCompanies(List.of(), "第一列", 0, -1);
        }

        HeaderMatch header = findCompanyColumn(rows);
        int startRow = header == null ? 0 : header.rowIndex() + 1;
        int columnIndex = header == null ? 0 : header.columnIndex();
        String columnLabel = header == null ? "第一列" : "「" + rows.get(header.rowIndex()).get(header.columnIndex()) + "」列";

        LinkedHashSet<String> companies = new LinkedHashSet<>();
        for (int i = startRow; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (columnIndex >= row.size()) {
                continue;
            }
            String company = normalize(row.get(columnIndex));
            if (isCompanyValue(company)) {
                companies.add(company);
            }
            if (companies.size() >= MAX_COMPANIES) {
                break;
            }
        }
        return new ParsedCompanies(new ArrayList<>(companies), columnLabel, columnIndex, header == null ? -1 : header.rowIndex());
    }

    private HeaderMatch findCompanyColumn(List<List<String>> rows) {
        HeaderMatch best = null;
        int maxRows = Math.min(rows.size(), MAX_HEADER_ROWS);
        for (int rowIndex = 0; rowIndex < maxRows; rowIndex++) {
            List<String> row = rows.get(rowIndex);
            for (int columnIndex = 0; columnIndex < row.size(); columnIndex++) {
                int score = companyHeaderScore(row.get(columnIndex));
                if (score <= 0) {
                    continue;
                }
                if (best == null || score > best.score()) {
                    best = new HeaderMatch(rowIndex, columnIndex, score);
                }
            }
        }
        return best;
    }

    private int companyHeaderScore(String value) {
        String normalized = normalize(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\-_（）()【】\\[\\]：:]", "");
        if (normalized.isBlank()) {
            return 0;
        }
        if (normalized.equals("企业名称") || normalized.equals("公司名称") || normalized.equals("单位名称")) {
            return 100;
        }
        if (normalized.contains("企业名称") || normalized.contains("公司名称") || normalized.contains("单位名称")) {
            return 95;
        }
        if (normalized.equals("企业名") || normalized.equals("公司名") || normalized.equals("目标公司")) {
            return 90;
        }
        if (normalized.contains("公司") || normalized.contains("企业") || normalized.contains("雇主")) {
            return 80;
        }
        if (normalized.equals("company") || normalized.equals("companyname") || normalized.equals("employer")) {
            return 80;
        }
        if (normalized.contains("organization") || normalized.contains("organisation")) {
            return 70;
        }
        return 0;
    }

    private boolean isCompanyValue(String value) {
        String normalized = normalize(value);
        if (normalized.length() < 2 || normalized.length() > 120) {
            return false;
        }
        String compact = normalized.replaceAll("[\\s\\-_（）()【】\\[\\]：:]", "");
        if (isHeaderLikeValue(compact)) {
            return false;
        }
        if (PURE_NUMBER_PATTERN.matcher(compact).matches()) {
            return false;
        }
        if (CREDIT_CODE_PATTERN.matcher(compact.toUpperCase(Locale.ROOT)).matches()) {
            return false;
        }
        return !Set.of("序号", "编号", "类型", "地区", "所属县区", "统一社会信用代码", "名称", "备注").contains(compact);
    }

    private boolean isHeaderLikeValue(String compact) {
        return Set.of(
                "公司", "公司名", "公司名称", "公司全称", "目标公司",
                "企业", "企业名", "企业名称",
                "单位", "单位名称", "雇主",
                "company", "companyname", "employer", "organization", "organisation"
        ).contains(compact.toLowerCase(Locale.ROOT));
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }

    private boolean looksLikeUtf8(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        return !text.contains("\uFFFD");
    }

    private String stripBom(String text) {
        if (text != null && text.startsWith("\uFEFF")) {
            return text.substring(1);
        }
        return text == null ? "" : text;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return SPACE_PATTERN.matcher(value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' '))
                .replaceAll(" ")
                .trim();
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private record HeaderMatch(int rowIndex, int columnIndex, int score) {}

    private record ParsedCompanies(List<String> companies, String columnLabel, int columnIndex, int headerRow) {}
}
