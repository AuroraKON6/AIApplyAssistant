package com.getjobs.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.dto.DiscoveredJob;
import com.getjobs.application.dto.JobDiscoverRequest;
import com.getjobs.application.service.WebSearchService.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Comparator;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobDiscoveryService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_SKYVERN_BASE_URL = "http://127.0.0.1:8001";
    private static final int MIN_BROWSER_VERIFIED_JOBS_BEFORE_WEB_BACKUP = 5;
    private final ConfigService configService;
    private final WebSearchService webSearchService;
    private final Map<String, DiscoveryTask> discoveryTasks = new ConcurrentHashMap<>();

    public List<DiscoveredJob> discover(JobDiscoverRequest request) {
        return discoverInternal(request, null);
    }

    public Map<String, Object> startDiscovery(JobDiscoverRequest request) {
        if (request == null || isBlank(request.goal())) {
            throw new IllegalArgumentException("请描述你想找的工作或实习。");
        }

        String id = UUID.randomUUID().toString().substring(0, 12);
        DiscoveryTask task = new DiscoveryTask(id);
        discoveryTasks.put(id, task);

        CompletableFuture.runAsync(() -> {
            try {
                task.status = "running";
                task.message = "正在启动真实浏览器查找岗位...";
                touch(task);
                List<DiscoveredJob> jobs = discoverInternal(request, task);
                if (task.cancelRequested) {
                    task.status = "cancelled";
                    task.message = "已停止真实查找任务。";
                    return;
                }
                task.jobs = jobs;
                task.status = "completed";
                task.message = jobs.isEmpty()
                        ? "没有找到浏览器验证过的岗位详情页。可以放宽城市、岗位关键词，或上传公司名单后重试。"
                        : "已找到 " + jobs.size() + " 个真实候选岗位。";
            } catch (CancellationException e) {
                task.status = "cancelled";
                task.message = "已停止真实查找任务。";
            } catch (Exception e) {
                if (task.cancelRequested) {
                    task.status = "cancelled";
                    task.message = "已停止真实查找任务。";
                    return;
                }
                task.status = "failed";
                task.error = e.getMessage();
                task.message = "查找岗位失败。";
                log.warn("Async job discovery failed: {}", e.getMessage(), e);
            } finally {
                touch(task);
            }
        });

        return taskSnapshot(task);
    }

    public Map<String, Object> getDiscoveryStatus(String id) {
        if (isBlank(id)) {
            throw new IllegalArgumentException("查找任务 ID 不能为空。");
        }
        DiscoveryTask task = discoveryTasks.get(id.trim());
        if (task == null) {
            throw new IllegalArgumentException("查找任务不存在或已过期。");
        }
        return taskSnapshot(task);
    }

    public Map<String, Object> cancelDiscovery(String id) {
        if (isBlank(id)) {
            throw new IllegalArgumentException("查找任务 ID 不能为空。");
        }
        DiscoveryTask task = discoveryTasks.get(id.trim());
        if (task == null) {
            throw new IllegalArgumentException("查找任务不存在或已过期。");
        }
        task.cancelRequested = true;
        task.status = "cancelled";
        task.message = "已停止真实查找任务。";

        if (!isBlank(task.skyvernRunId)) {
            try {
                String baseUrl = trimTrailingSlash(configuredValue("SKYVERN_BASE_URL", DEFAULT_SKYVERN_BASE_URL));
                String apiKey = configuredValue("SKYVERN_API_KEY", "");
                postSkyvernJson(baseUrl + "/v1/runs/" + task.skyvernRunId + "/cancel", apiKey, new JSONObject());
                task.skyvernStatus = "cancelled";
            } catch (Exception e) {
                task.error = "停止 Skyvern 任务失败: " + e.getMessage();
                log.warn("Failed to cancel Skyvern discovery run {}: {}", task.skyvernRunId, e.getMessage());
            }
        }

        touch(task);
        return taskSnapshot(task);
    }

    private List<DiscoveredJob> discoverInternal(JobDiscoverRequest request, DiscoveryTask task) {
        if (request == null || isBlank(request.goal())) {
            throw new IllegalArgumentException("请描述你想找的工作或实习。");
        }

        String baseUrl = requireConfig("LLM_BASE_URL");
        String apiKey = requireConfig("LLM_API_KEY");
        String modelName = requireConfig("LLM_MODEL_NAME");

        boolean hasCompanyLeads = hasCompanyLeads(request);

        // Phase 1: Use Skyvern as the primary source of truth. The browser must open
        // candidate pages before they become selectable jobs.
        ensureNotCancelled(task);
        updateTask(task, hasCompanyLeads
                ? "正在按用户上传的公司名单，用真实浏览器查找官网和招聘页..."
                : "正在用 Skyvern 真实浏览器打开搜索页和招聘站...");
        List<DiscoveredJob> jobs = discoverWithSkyvern(request, modelName, task);

        ensureNotCancelled(task);
        if (hasCompanyLeads) {
            jobs.sort(Comparator.comparingInt(j -> channelPriority(j.channelType())));
            if (jobs.isEmpty()) {
                updateTask(task, "目标公司名单已查找，但没有发现浏览器验证过的相似岗位。可以减少公司数量、放宽岗位方向或稍后重试。");
            }
            return jobs;
        }

        if (jobs.size() >= MIN_BROWSER_VERIFIED_JOBS_BEFORE_WEB_BACKUP) {
            jobs.sort(Comparator.comparingInt(j -> channelPriority(j.channelType())));
            return jobs;
        }

        // Phase 2: If the browser found too few jobs, use ordinary search only as
        // supplemental leads. These results still must pass strict URL filtering.
        updateTask(task, jobs.isEmpty()
                ? "浏览器暂时没有整理出岗位，正在用网页搜索补充线索..."
                : "浏览器已找到 " + jobs.size() + " 个岗位，正在补充更多候选线索...");
        List<String> queries = generateSearchQueries(baseUrl, apiKey, modelName, request);
        log.info("Generated {} search queries: {}", queries.size(), queries);

        ensureNotCancelled(task);
        updateTask(task, "正在搜索网页补充候选岗位...");
        List<SearchResult> allResults = new ArrayList<>();
        for (String query : queries) {
            ensureNotCancelled(task);
            List<SearchResult> results = webSearchService.search(query, 8);
            log.info("Query '{}' returned {} results", query, results.size());
            allResults.addAll(results);
        }

        // Deduplicate by URL
        List<SearchResult> uniqueResults = allResults.stream()
                .filter(r -> r.url() != null && r.url().startsWith("http"))
                .collect(Collectors.toMap(
                        SearchResult::url,
                        r -> r,
                        (a, b) -> a,
                        LinkedHashMap::new
                ))
                .values().stream().toList();

        log.info("Total unique search results: {}", uniqueResults.size());
        for (int i = 0; i < uniqueResults.size(); i++) {
            SearchResult r = uniqueResults.get(i);
            log.info("Unique result {}: url={}, title={}", i + 1, r.url(), r.title());
        }

        if (uniqueResults.isEmpty()) {
            log.info("No supplemental search results found");
            jobs.sort(Comparator.comparingInt(j -> channelPriority(j.channelType())));
            return jobs;
        }

        ensureNotCancelled(task);
        updateTask(task, "正在筛掉搜索页、首页和不可靠结果...");
        List<DiscoveredJob> supplementalJobs = filterAndStructure(baseUrl, apiKey, modelName, request, uniqueResults);
        jobs = mergeJobs(jobs, supplementalJobs);

        // Sort: ATS > company_site > job_board > manual_required
        jobs.sort(Comparator.comparingInt(j -> channelPriority(j.channelType())));

        return jobs;
    }

    private List<String> generateSearchQueries(String baseUrl, String apiKey, String modelName, JobDiscoverRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a search query generator for job hunting.\n");
        sb.append("Based on the user's job search goal, generate 5-8 search engine queries to find REAL job postings.\n\n");
        sb.append("User goal: ").append(request.goal().trim()).append("\n");

        if (request.companyNames() != null && !request.companyNames().isEmpty()) {
            List<String> companies = request.companyNames().stream()
                    .filter(c -> !isBlank(c))
                    .map(String::trim)
                    .toList();
            if (!companies.isEmpty()) {
                sb.append("Company hints: ").append(String.join(", ", companies)).append("\n");
            }
        }

        if (!isBlank(request.extraNotes())) {
            sb.append("Additional notes: ").append(request.extraNotes().trim()).append("\n");
        }

        sb.append("\nGenerate queries that will find REAL, specific job listing pages (not search pages or homepages).\n");
        sb.append("IMPORTANT: Always include the Chinese name of the job board, not just the domain.\n");
        sb.append("Focus on Chinese job boards and company career pages:\n");
        sb.append("- Boss直聘 (zhipin.com)\n");
        sb.append("- 猎聘 (liepin.com)\n");
        sb.append("- 前程无忧/51job (51job.com)\n");
        sb.append("- 智联招聘 (zhaopin.com)\n");
        sb.append("- 牛客网 (nowcoder.com)\n");
        sb.append("- 拉勾网 (lagou.com)\n\n");
        sb.append("Good examples:\n");
        sb.append("- 'Boss直聘 Python 后端 实习 上海'\n");
        sb.append("- '猎聘 Python后端实习生'\n");
        sb.append("- '前程无忧 上海 Python实习'\n");
        sb.append("- '智联招聘 Python后端开发实习'\n");
        sb.append("- '字节跳动 招聘 后端'\n");
        sb.append("- '华为 校园招聘 大数据'\n");
        sb.append("- '美团 招聘 运营实习'\n\n");
        sb.append("Bad examples (do NOT use):\n");
        sb.append("- 'site:zhipin.com Python 后端' (site: operator not supported)\n");
        sb.append("- 'Python 后端 实习 zhipin.com' (domain alone doesn't work well)\n");
        sb.append("- 'Lever careers backend intern' (generic ATS search pages are useless)\n");
        sb.append("- 'Greenhouse Python developer intern' (returns search pages, not real jobs)\n");
        sb.append("- 'Ashby careers intern' (returns generic boards, not specific listings)\n\n");
        sb.append("For company hints, add queries like '公司名 招聘' or '公司名 官网 招聘'.\n");
        sb.append("NEVER generate queries for generic ATS search pages (Lever, Greenhouse, Ashby boards). Only generate queries that lead to specific job detail pages.\n");
        sb.append("Return ONLY a JSON object: {\"queries\": [\"query1\", \"query2\", ...]}\n");

        String raw = callLlm(baseUrl, apiKey, modelName, sb.toString());
        try {
            String cleaned = cleanJsonResponse(raw);
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(cleaned, new TypeReference<>() {});
            Object queriesObj = parsed.get("queries");
            if (queriesObj instanceof List<?> list) {
                List<String> queries = list.stream()
                        .map(Object::toString)
                        .filter(q -> !q.isBlank())
                        .toList();
                if (!queries.isEmpty()) return queries;
            }
        } catch (Exception e) {
            log.warn("Failed to parse search queries from LLM: {}", raw, e);
        }

        // Fallback: generate basic queries
        return fallbackQueries(request);
    }

    private List<String> fallbackQueries(JobDiscoverRequest request) {
        List<String> queries = new ArrayList<>();
        String goal = request.goal().trim();

        queries.add("Boss直聘 " + goal);
        queries.add("猎聘 " + goal);
        queries.add("前程无忧 " + goal);
        queries.add("智联招聘 " + goal);
        queries.add(goal + " 招聘");

        if (request.companyNames() != null) {
            for (String company : request.companyNames().stream().filter(c -> !isBlank(c)).limit(3).toList()) {
                queries.add(company.trim() + " 招聘 " + goal);
            }
        }
        return queries;
    }

    private List<DiscoveredJob> filterAndStructure(String baseUrl, String apiKey, String modelName,
                                                    JobDiscoverRequest request, List<SearchResult> searchResults) {
        StringBuilder resultsText = new StringBuilder();
        for (int i = 0; i < searchResults.size() && i < 60; i++) {
            SearchResult r = searchResults.get(i);
            resultsText.append("[").append(i + 1).append("] ").append(r.title()).append("\n");
            resultsText.append("URL: ").append(r.url()).append("\n");
            if (!isBlank(r.snippet())) {
                resultsText.append("摘要: ").append(r.snippet()).append("\n");
            }
            resultsText.append("\n");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("You are a job matching assistant. Below are REAL search results from the web.\n");
        sb.append("Select only the results that are genuine job or internship postings matching the user's goal.\n");
        sb.append("DO NOT invent or fabricate any information. Only use what appears in the search results.\n\n");
        sb.append("User goal: ").append(request.goal().trim()).append("\n");
        if (!isBlank(request.extraNotes())) {
            sb.append("Notes: ").append(request.extraNotes().trim()).append("\n");
        }
        sb.append("\nSearch results:\n");
        sb.append(resultsText);

        sb.append("\nFor each matching job, extract:\n");
        sb.append("- company: company name (from the result)\n");
        sb.append("- title: job title\n");
        sb.append("- location: city/location\n");
        sb.append("- url: the EXACT URL from the search result (do not modify or fabricate)\n");
        sb.append("- matchReason: why this matches the user's goal (1-2 sentences)\n");
        sb.append("- source: which platform (Boss直聘/猎聘/前程无忧/LinkedIn/公司官网/Greenhouse/etc)\n");
        sb.append("- confidence: 'high' if clearly a job posting, 'medium' if likely, 'low' if uncertain\n");
        sb.append("- evidenceText: a short quote from the search snippet that proves this is a real job posting\n\n");
        sb.append("Return ONLY a JSON object: {\"jobs\": [...], \"note\": \"optional note if few results found\"}\n");
        sb.append("If no real job postings found, return {\"jobs\": [], \"note\": \"reason\"}\n");

        String raw = callLlm(baseUrl, apiKey, modelName, sb.toString());
        log.info("LLM filter response (first 500 chars): {}", raw.length() > 500 ? raw.substring(0, 500) : raw);
        try {
            String cleaned = cleanJsonResponse(raw);
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(cleaned, new TypeReference<>() {});
            Object jobsObj = parsed.get("jobs");
            if (jobsObj instanceof List<?> list) {
                log.info("LLM returned {} jobs", list.size());
                String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                List<DiscoveredJob> jobs = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        String company = str(map.get("company"));
                        String title = str(map.get("title"));
                        String url = str(map.get("url"));
                        if (isBlank(company) && isBlank(title)) continue;
                        if (isBlank(url)) continue;
                        if (!isLikelySpecificJobUrl(url)) {
                            log.info("Skipping generic/search URL from LLM result: {}", url);
                            continue;
                        }

                        // Verify URL came from search results
                        String finalUrl = url;
                        boolean urlInResults = searchResults.stream()
                                .anyMatch(r -> r.url().equals(finalUrl) || r.url().startsWith(finalUrl));
                        if (!urlInResults) {
                            log.warn("LLM returned URL not in search results, skipping: {}", url);
                            continue;
                        }

                        String confidence = str(map.get("confidence"));
                        if (isBlank(confidence)) confidence = "medium";

                        PlatformClassifier.PlatformClass pc = PlatformClassifier.classify(url, str(map.get("source")));
                        String targetCompany = matchingCompanyName(company, request.companyNames());
                        DiscoveredJob candidate = new DiscoveredJob(
                                UUID.randomUUID().toString().substring(0, 8),
                                company,
                                title,
                                str(map.get("location")),
                                url,
                                str(map.get("matchReason")),
                                str(map.get("source")),
                                now,
                                confidence,
                                str(map.get("evidenceText")),
                                pc.channelType(),
                                pc.deliveryDifficulty(),
                                "unverified",
                                "等待页面校验",
                                targetCompany,
                                "",
                                "",
                                isBlank(targetCompany) ? "web_search" : "company_lead_matched"
                        );
                        DiscoveredJob verified = verifyCandidateJob(candidate, false);
                        if (verified != null) {
                            jobs.add(verified);
                        } else {
                            log.info("Skipping supplemental result that did not pass page verification: {}", url);
                        }
                    }
                }
                return jobs;
            }
        } catch (Exception e) {
            log.warn("Failed to parse filtered jobs from LLM: {}", raw, e);
        }

        return List.of();
    }

    private List<DiscoveredJob> discoverWithSkyvern(JobDiscoverRequest request, String modelName, DiscoveryTask task) {
        String baseUrl = trimTrailingSlash(configuredValue("SKYVERN_BASE_URL", DEFAULT_SKYVERN_BASE_URL));
        String apiKey = configuredValue("SKYVERN_API_KEY", "");
        String startingUrl = buildSkyvernStartingUrl(request);
        String prompt = buildSkyvernDiscoveryPrompt(request);

        JSONObject body = new JSONObject();
        body.put("prompt", prompt);
        body.put("url", startingUrl);
        body.put("title", "真实岗位查找 - " + truncate(request.goal().trim(), 36));
        body.put("engine", configuredValue("SKYVERN_ENGINE", "skyvern-2.0"));
        body.put("proxy_location", "NONE");
        if (!isBlank(modelName)) {
            body.put("model", new JSONObject(Map.of("model_name", modelName)));
        }
        body.put("data_extraction_schema", new JSONObject(discoveryOutputSchema()));

        try {
            Map<String, Object> created = postSkyvernJson(baseUrl + "/v1/run/tasks", apiKey, body);
            String runId = str(created.get("run_id"));
            if (isBlank(runId)) {
                log.warn("Skyvern discovery did not return run_id: {}", created);
                return List.of();
            }
            log.info("Skyvern discovery started: {}", runId);
            if (task != null) {
                task.skyvernRunId = runId;
                task.skyvernAppUrl = str(created.get("app_url"));
                task.runStatusUrl = "/api/skyvern/runs/" + runId;
                task.message = "Skyvern 正在真实浏览网页查找岗位，这一步可能需要几分钟。";
                touch(task);
            }

            Map<String, Object> finalRun = waitForSkyvernRun(baseUrl, apiKey, runId, task);
            String status = str(finalRun.get("status")).toLowerCase();
            if (!"completed".equals(status)) {
                log.warn("Skyvern discovery ended with status {}: {}", status, finalRun.get("failure_reason"));
                updateTask(task, "Skyvern 查找未完成：" + status);
                return List.of();
            }
            updateTask(task, "Skyvern 已完成真实浏览，正在整理岗位结果...");
            return parseSkyvernDiscoveredJobs(finalRun, request);
        } catch (Exception e) {
            log.warn("Skyvern discovery failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private String buildSkyvernStartingUrl(JobDiscoverRequest request) {
        String query = request.goal().trim() + " 招聘 实习 岗位";
        List<String> companies = normalizedCompanyLeads(request, 1);
        if (!companies.isEmpty()) {
            query = companies.get(0) + " 官网 招聘 校园招聘 " + request.goal().trim();
        }
        return "https://cn.bing.com/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    private String buildSkyvernDiscoveryPrompt(JobDiscoverRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are controlling a real browser to find real job or internship postings.\n");
        sb.append("User goal: ").append(request.goal().trim()).append("\n");
        List<String> companyLeads = normalizedCompanyLeads(request, 80);
        if (!companyLeads.isEmpty()) {
            String companies = String.join(", ", companyLeads);
            if (!companies.isBlank()) {
                sb.append("Target companies from the user's uploaded spreadsheet: ").append(companies).append("\n");
            }
        }
        if (!isBlank(request.extraNotes())) {
            sb.append("Extra notes: ").append(request.extraNotes().trim()).append("\n");
        }
        sb.append("\nBrowser task:\n");
        if (!companyLeads.isEmpty()) {
            sb.append("1. Target-company mode is enabled because the user uploaded a spreadsheet of companies they want to apply to.\n");
            sb.append("2. Process the listed companies one by one. For each company, search for its official website first, then find its official careers/recruitment/campus recruitment page.\n");
            sb.append("3. Compare available roles against the user's goal. The user is interested only in similar jobs or internships, not every job at the company.\n");
            sb.append("4. Prefer official company career pages and official ATS pages. Use famous job boards only if the official page is unavailable, blocked, or has no visible positions.\n");
            sb.append("5. Do not return a company just because it exists. Return only verified job detail pages or direct official application pages for similar roles.\n");
            sb.append("6. Do not invent an official website, careers page, or job. If you cannot verify a company's official page or matching roles, skip it and continue to the next company.\n");
            sb.append("7. For each returned job, include targetCompany, officialWebsite, careersPage, and companySearchStatus. companySearchStatus should briefly say how you found it, such as official_site_role_found, official_site_no_matching_role, blocked_by_login, or job_board_fallback.\n");
            sb.append("8. Return 5 to 12 real postings if possible. Fewer is acceptable if fewer are verified.\n");
            sb.append("9. evidenceText must be visible text copied or summarized from the opened job detail page, such as title, company, location, or application deadline.\n");
        } else {
            sb.append("1. Use the browser to search the web and job boards for the user's goal. Include Chinese job boards such as 智联招聘, Boss直聘, 猎聘, 前程无忧, plus company career pages and ATS pages when relevant.\n");
            sb.append("2. Open promising results and verify they are actual job detail pages or official recruitment detail pages. A result is not verified until the browser has opened the page and the role title/company are visible.\n");
            sb.append("3. If you land on a search page, ATS board page, job-board list page, company homepage, or careers index, click into a matching job detail first. Never return the list/search/homepage URL.\n");
            sb.append("4. Return 5 to 12 real postings if possible. Fewer is acceptable if fewer are verified.\n");
            sb.append("5. Do NOT return generic Lever/Ashby/Greenhouse search pages, job-board homepages, company homepages, search engine pages, or URLs where the user still has to search manually.\n");
            sb.append("6. Each returned URL must be a page that a user can open and see the specific job or internship, or a direct official application page for that role.\n");
            sb.append("7. If a site blocks access with login/CAPTCHA/anti-bot, record that source as blocked and move to another source. Do not invent jobs behind blocked pages.\n");
            sb.append("8. Prefer roles in the cities/remote preferences mentioned by the user, and prefer official company career pages/ATS pages over scraped reposts when both are available.\n");
            sb.append("9. evidenceText must be visible text copied or summarized from the opened job detail page, such as title, company, location, or application deadline.\n");
        }
        sb.append("\nReturn structured data only through the extraction schema. Use confidence high only when the role title/company/source were visible in the browser on a specific job page.\n");
        return sb.toString();
    }

    private Map<String, Object> discoveryOutputSchema() {
        Map<String, Object> job = new LinkedHashMap<>();
        Map<String, Object> jobProperties = new LinkedHashMap<>();
        jobProperties.put("company", Map.of("type", "string"));
        jobProperties.put("title", Map.of("type", "string"));
        jobProperties.put("location", Map.of("type", "string"));
        jobProperties.put("url", Map.of("type", "string"));
        jobProperties.put("matchReason", Map.of("type", "string"));
        jobProperties.put("source", Map.of("type", "string"));
        jobProperties.put("confidence", Map.of("type", "string"));
        jobProperties.put("evidenceText", Map.of("type", "string"));
        jobProperties.put("targetCompany", Map.of("type", "string"));
        jobProperties.put("officialWebsite", Map.of("type", "string"));
        jobProperties.put("careersPage", Map.of("type", "string"));
        jobProperties.put("companySearchStatus", Map.of("type", "string"));
        job.put("type", "object");
        job.put("properties", jobProperties);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "jobs", Map.of("type", "array", "items", job),
                "summary", Map.of("type", "string"),
                "blockedSources", Map.of("type", "array", "items", Map.of("type", "string"))
        ));
        return schema;
    }

    private Map<String, Object> waitForSkyvernRun(String baseUrl, String apiKey, String runId, DiscoveryTask task) throws InterruptedException {
        Map<String, Object> current = Map.of();
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(30).toMillis();
        while (System.currentTimeMillis() < deadline) {
            ensureNotCancelled(task);
            current = getSkyvernJson(baseUrl + "/v1/runs/" + runId, apiKey);
            String status = str(current.get("status")).toLowerCase();
            if (task != null) {
                task.skyvernStatus = status;
                task.message = "Skyvern 真实浏览查找中：" + (status.isBlank() ? "运行中" : status);
                if (isTerminalSkyvernStatus(status)) {
                    task.message = "Skyvern 浏览任务已结束：" + status;
                }
                touch(task);
            }
            if (isTerminalSkyvernStatus(status)) {
                return current;
            }
            Thread.sleep(5000);
        }
        log.warn("Skyvern discovery timed out: {}", runId);
        return current;
    }

    private List<DiscoveredJob> parseSkyvernDiscoveredJobs(Map<String, Object> run, JobDiscoverRequest request) {
        Object output = run.get("output");
        Map<String, Object> outputMap = coerceMap(output);
        Object jobsObj = outputMap.get("jobs");
        if (!(jobsObj instanceof List<?> list)) {
            return List.of();
        }

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        List<DiscoveredJob> jobs = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> map = coerceMap(item);
            if (map.isEmpty()) continue;

            String company = str(map.get("company"));
            String title = str(map.get("title"));
            String url = str(map.get("url"));
            if ((isBlank(company) && isBlank(title)) || isBlank(url)) continue;
            if (!isLikelySpecificJobUrl(url)) {
                log.info("Skipping generic/search URL from Skyvern discovery: {}", url);
                continue;
            }

            String confidence = str(map.get("confidence"));
            if (isBlank(confidence)) confidence = "medium";
            PlatformClassifier.PlatformClass pc = PlatformClassifier.classify(url, str(map.get("source")));
            String source = nonBlank(str(map.get("source")), "真实浏览");
            if (!source.toLowerCase().startsWith("skyvern")) {
                source = "Skyvern · " + source;
            }
            String targetCompany = nonBlank(
                    str(map.get("targetCompany")),
                    matchingCompanyName(nonBlank(company, str(map.get("company"))), request.companyNames())
            );
            DiscoveredJob candidate = new DiscoveredJob(
                    UUID.randomUUID().toString().substring(0, 8),
                    company,
                    title,
                    str(map.get("location")),
                    url,
                    str(map.get("matchReason")),
                    source,
                    now,
                    confidence,
                    str(map.get("evidenceText")),
                    pc.channelType(),
                    pc.deliveryDifficulty(),
                    "browser_verified",
                    "Skyvern 已在真实浏览器中打开并提取该岗位。",
                    targetCompany,
                    str(map.get("officialWebsite")),
                    str(map.get("careersPage")),
                    nonBlank(str(map.get("companySearchStatus")), isBlank(targetCompany) ? "browser_search" : "target_company_browser_search")
            );
            DiscoveredJob verified = verifyCandidateJob(candidate, true);
            if (verified != null) {
                jobs.add(verified);
            }
        }
        return jobs;
    }

    private DiscoveredJob verifyCandidateJob(DiscoveredJob job, boolean browserVerified) {
        PageVerification verification = verifyJobPage(job);
        if (verification.ok()) {
            return withVerification(job, "page_verified", verification.note(), job.confidence());
        }
        if (browserVerified && !isBlank(job.evidenceText())) {
            String confidence = "high".equalsIgnoreCase(job.confidence()) ? "medium" : job.confidence();
            return withVerification(job, "browser_verified",
                    "Skyvern 已在浏览器中看到岗位；静态页面校验未通过：" + verification.note(),
                    confidence);
        }
        return null;
    }

    private PageVerification verifyJobPage(DiscoveredJob job) {
        if (job == null || isBlank(job.url())) {
            return new PageVerification(false, "缺少岗位链接。");
        }
        try {
            BlockingHttpClient.Response response = BlockingHttpClient.get(
                    job.url().trim(),
                    Map.of(
                            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                            "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                            "Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8"
                    ),
                    10
            );
            int status = response.statusCode();
            if (status == 401 || status == 403 || status == 429) {
                return new PageVerification(false, "页面需要登录或触发风控，HTTP " + status + "。");
            }
            if (status < 200 || status >= 400) {
                return new PageVerification(false, "页面返回 HTTP " + status + "。");
            }

            String text = normalizePageText(response.body());
            if (text.length() < 160) {
                return new PageVerification(false, "页面文字过少，可能不是岗位详情页。");
            }
            if (looksBlockedOrLogin(text)) {
                return new PageVerification(false, "页面显示登录、验证码或访问验证。");
            }

            boolean hasJobWords = containsAny(text,
                    "职位", "岗位", "招聘", "申请", "投递", "实习", "校招", "社招",
                    "job", "jobs", "career", "careers", "apply", "application", "intern", "internship", "position", "opening");
            boolean titleSeen = containsMeaningfulText(text, job.title());
            boolean companySeen = containsMeaningfulText(text, job.company());
            boolean atsSpecific = isSpecificAtsUrl(job.url());

            if (hasJobWords && (titleSeen || companySeen || atsSpecific)) {
                List<String> parts = new ArrayList<>();
                if (titleSeen) parts.add("岗位名可见");
                if (companySeen) parts.add("公司名可见");
                if (atsSpecific && parts.isEmpty()) parts.add("ATS 详情链接可打开");
                return new PageVerification(true, "页面校验通过：" + String.join("、", parts) + "。");
            }
            return new PageVerification(false, "页面没有检测到足够的岗位详情文字。");
        } catch (Exception e) {
            return new PageVerification(false, "页面静态校验失败：" + truncate(str(e.getMessage()), 90));
        }
    }

    private DiscoveredJob withVerification(DiscoveredJob job, String status, String note, String confidence) {
        return new DiscoveredJob(
                job.id(),
                job.company(),
                job.title(),
                job.location(),
                job.url(),
                job.matchReason(),
                job.source(),
                job.checkedAt(),
                nonBlank(confidence, job.confidence()),
                job.evidenceText(),
                job.channelType(),
                job.deliveryDifficulty(),
                status,
                note,
                job.targetCompany(),
                job.officialWebsite(),
                job.careersPage(),
                job.companySearchStatus()
        );
    }

    private List<DiscoveredJob> mergeJobs(List<DiscoveredJob> first, List<DiscoveredJob> second) {
        Map<String, DiscoveredJob> byUrl = new LinkedHashMap<>();
        for (DiscoveredJob job : first) {
            if (!isBlank(job.url()) && isLikelySpecificJobUrl(job.url())) {
                byUrl.put(job.url(), job);
            }
        }
        for (DiscoveredJob job : second) {
            if (!isBlank(job.url()) && isLikelySpecificJobUrl(job.url())) {
                byUrl.putIfAbsent(job.url(), job);
            }
        }
        return new ArrayList<>(byUrl.values());
    }

    private Map<String, Object> postSkyvernJson(String url, String apiKey, JSONObject body) {
        return sendJson(BlockingHttpClient.postJson(url, skyvernHeaders(apiKey), body.toString(), 120));
    }

    private Map<String, Object> getSkyvernJson(String url, String apiKey) {
        return sendJson(BlockingHttpClient.get(url, skyvernHeaders(apiKey), 30));
    }

    private Map<String, Object> sendJson(BlockingHttpClient.Response response) {
        try {
            String body = response.body() == null ? "" : response.body();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("HTTP " + response.statusCode() + ": " + body);
            }
            if (body.isBlank()) {
                return new LinkedHashMap<>();
            }
            return OBJECT_MAPPER.readValue(body, new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> coerceMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return OBJECT_MAPPER.readValue(text, new TypeReference<>() {});
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }

    private boolean isTerminalSkyvernStatus(String status) {
        return status.equals("completed") || status.equals("failed")
                || status.equals("cancelled") || status.equals("canceled")
                || status.equals("terminated") || status.equals("timed_out");
    }

    private void updateTask(DiscoveryTask task, String message) {
        if (task == null) {
            return;
        }
        task.message = message;
        touch(task);
    }

    private void ensureNotCancelled(DiscoveryTask task) {
        if (task != null && task.cancelRequested) {
            throw new CancellationException("查找任务已停止。");
        }
    }

    private void touch(DiscoveryTask task) {
        task.updatedAt = nowText();
    }

    private Map<String, Object> taskSnapshot(DiscoveryTask task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", task.id);
        result.put("status", task.status);
        result.put("message", task.message);
        result.put("error", task.error);
        result.put("jobs", task.jobs);
        result.put("jobCount", task.jobs == null ? 0 : task.jobs.size());
        result.put("createdAt", task.createdAt);
        result.put("updatedAt", task.updatedAt);
        result.put("skyvernRunId", task.skyvernRunId);
        result.put("skyvernStatus", task.skyvernStatus);
        result.put("skyvernAppUrl", task.skyvernAppUrl);
        result.put("runStatusUrl", task.runStatusUrl);
        return result;
    }

    private String nowText() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static class DiscoveryTask {
        private final String id;
        private volatile String status = "queued";
        private volatile String message = "等待开始查找岗位。";
        private volatile String error = "";
        private volatile List<DiscoveredJob> jobs = List.of();
        private final String createdAt;
        private volatile String updatedAt;
        private volatile String skyvernRunId = "";
        private volatile String skyvernStatus = "";
        private volatile String skyvernAppUrl = "";
        private volatile String runStatusUrl = "";
        private volatile boolean cancelRequested = false;

        private DiscoveryTask(String id) {
            this.id = id;
            this.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            this.updatedAt = this.createdAt;
        }
    }

    private String callLlm(String baseUrl, String apiKey, String modelName, String prompt) {
        String url = trimTrailingSlash(baseUrl) + "/chat/completions";

        JSONObject body = new JSONObject();
        body.put("model", modelName);
        body.put("temperature", 0.2);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject(Map.of("role", "system", "content",
                "You are a helpful assistant. Always respond with valid JSON only, no markdown formatting.")));
        messages.put(new JSONObject(Map.of("role", "user", "content", prompt)));
        body.put("messages", messages);

        JSONObject responseFormat = new JSONObject();
        responseFormat.put("type", "json_object");
        body.put("response_format", responseFormat);

        try {
            BlockingHttpClient.Response response = BlockingHttpClient.postJson(
                    url,
                    Map.of("Authorization", "Bearer " + apiKey),
                    body.toString(),
                    120
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("AI 模型返回 HTTP " + response.statusCode() + ": " + response.body());
            }

            Map<String, Object> parsed = OBJECT_MAPPER.readValue(response.body(), new TypeReference<>() {});
            Object choices = parsed.get("choices");
            if (choices instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> choice) {
                    Object message = choice.get("message");
                    if (message instanceof Map<?, ?> msg) {
                        Object contentObj = msg.get("content");
                        String content = contentObj == null ? "" : String.valueOf(contentObj);
                        if (!content.isBlank()) {
                            return content;
                        }
                    }
                }
            }
            throw new RuntimeException("AI 模型未返回有效内容。");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("调用 AI 模型失败: " + e.getMessage(), e);
        }
    }

    private String cleanJsonResponse(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return cleaned;
    }

    private String requireConfig(String key) {
        String value = configService.getConfigValue(key);
        if (isBlank(value)) {
            throw new IllegalArgumentException("请先在 AI配置 页面配置 " + configKeyToLabel(key) + "。");
        }
        return value.trim();
    }

    private String configuredValue(String key, String fallback) {
        String value = configService.getConfigValue(key);
        return isBlank(value) ? fallback : value.trim();
    }

    private String configKeyToLabel(String key) {
        return switch (key) {
            case "LLM_BASE_URL" -> "API 地址";
            case "LLM_API_KEY" -> "API Key";
            case "LLM_MODEL_NAME" -> "AI 模型名称";
            default -> key;
        };
    }

    private String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String nonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private boolean hasCompanyLeads(JobDiscoverRequest request) {
        return !normalizedCompanyLeads(request, 1).isEmpty();
    }

    private List<String> normalizedCompanyLeads(JobDiscoverRequest request, int limit) {
        if (request == null || request.companyNames() == null || request.companyNames().isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String company : request.companyNames()) {
            if (isBlank(company)) {
                continue;
            }
            String normalized = company.replaceAll("[\\r\\n\\t]+", " ")
                    .replaceAll("\\s{2,}", " ")
                    .trim();
            if (normalized.length() < 2 || result.contains(normalized)) {
                continue;
            }
            result.add(normalized);
            if (result.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return result;
    }

    private String matchingCompanyName(String company, List<String> companyNames) {
        if (companyNames == null || companyNames.isEmpty()) {
            return "";
        }
        String compactCompany = compactText(company);
        for (String lead : companyNames) {
            if (isBlank(lead)) {
                continue;
            }
            String compactLead = compactText(lead);
            if (compactLead.length() < 2) {
                continue;
            }
            if ((!compactCompany.isBlank() && (compactCompany.contains(compactLead) || compactLead.contains(compactCompany)))
                    || containsMeaningfulText(company, lead)) {
                return lead.trim();
            }
        }
        return "";
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String normalizePageText(String html) {
        if (html == null) {
            return "";
        }
        String text = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
        return text.length() > 60_000 ? text.substring(0, 60_000) : text;
    }

    private boolean looksBlockedOrLogin(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "captcha", "verify you are human", "access denied", "robot", "unusual traffic",
                "login required", "please sign in", "please log in", "please enable javascript",
                "验证码", "安全验证", "访问验证", "滑块", "请先登录", "扫码登录", "账号登录", "风险验证", "人机验证");
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (!isBlank(needle) && lower.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsMeaningfulText(String pageText, String candidate) {
        if (isBlank(pageText) || isBlank(candidate)) {
            return false;
        }
        String compactPage = compactText(pageText);
        String compactCandidate = compactText(candidate);
        if (compactCandidate.length() >= 3 && compactPage.contains(compactCandidate)) {
            return true;
        }

        int matched = 0;
        int considered = 0;
        for (String token : candidate.replaceAll("[^\\p{IsHan}A-Za-z0-9]+", " ").split("\\s+")) {
            String normalized = compactText(token);
            if (normalized.length() < 2 || isGenericJobToken(normalized)) {
                continue;
            }
            considered++;
            if (compactPage.contains(normalized)) {
                matched++;
            }
            if (matched >= 2 || (considered == 1 && normalized.length() >= 4 && matched == 1)) {
                return true;
            }
        }
        return false;
    }

    private String compactText(String value) {
        return value == null ? "" : value
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9]+", "");
    }

    private boolean isGenericJobToken(String token) {
        return List.of(
                "招聘", "岗位", "职位", "实习", "校招", "社招", "公司", "官网",
                "job", "jobs", "career", "careers", "apply", "intern", "internship", "position"
        ).contains(token);
    }

    private boolean isSpecificAtsUrl(String url) {
        if (isBlank(url)) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            String query = uri.getQuery() == null ? "" : uri.getQuery().toLowerCase(Locale.ROOT);
            if (host.contains("jobs.lever.co")) {
                return path.split("/").length >= 3 && !path.contains("/search");
            }
            if (host.contains("greenhouse.io")) {
                return path.contains("/jobs/") || query.contains("gh_jid=");
            }
            if (host.contains("ashbyhq.com")) {
                return path.contains("/jobs/");
            }
            if (host.contains("workdayjobs.com") || host.contains("myworkdayjobs.com")) {
                return path.contains("/job/");
            }
            return lower.contains("smartrecruiters.com") && path.contains("/jobs/");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isLikelySpecificJobUrl(String url) {
        if (isBlank(url)) {
            return false;
        }
        String lower = url.trim().toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }

        List<String> genericMarkers = List.of(
                "jobs.lever.co/search",
                "jobs.lever.co/?",
                "boards.greenhouse.io/embed/job_board",
                "boards.greenhouse.io/?",
                "jobs.ashbyhq.com?query=",
                "jobs.ashbyhq.com/?",
                "apply.workable.com",
                "sou.zhaopin.com",
                "zhaopin.com/sou",
                "zhaopin.com/?",
                "liepin.com/zhaopin",
                "search.51job.com",
                "zhipin.com/web/geek/job?query=",
                "bing.com/search",
                "baidu.com",
                "google.com/search",
                "duckduckgo.com",
                "indeed.com/q-",
                "indeed.com/jobs?",
                "linkedin.com/jobs/search",
                "linkedin.com/jobs/collection"
        );
        for (String marker : genericMarkers) {
            if (lower.contains(marker)) {
                return false;
            }
        }

        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
            String query = uri.getQuery() == null ? "" : uri.getQuery().toLowerCase();

            if (path.isBlank() || "/".equals(path)) {
                return false;
            }

            if (host.contains("zhipin.com")) {
                return lower.contains("job_detail") || path.contains("/job_detail/");
            }
            if (host.contains("zhaopin.com")) {
                return path.contains("/jobs/") || path.contains("/job/");
            }
            if (host.contains("liepin.com")) {
                return path.contains("/job/") || path.contains("/a/");
            }
            if (host.contains("51job.com")) {
                return lower.contains("jobs.51job.com") || path.contains("/job/");
            }
            if (host.contains("jobs.lever.co")) {
                String[] segments = path.split("/");
                return segments.length >= 3 && !path.contains("/search");
            }
            if (host.contains("greenhouse.io")) {
                return path.contains("/jobs/") || query.contains("gh_jid=");
            }
            if (host.contains("ashbyhq.com")) {
                return path.contains("/jobs/") || path.split("/").length >= 3;
            }
            if (host.contains("workdayjobs.com") || host.contains("myworkdayjobs.com")) {
                return path.contains("/job/");
            }

            return path.matches(".*(job|jobs|career|careers|position|opening|recruit|campus).*")
                    && path.length() > 8;
        } catch (Exception e) {
            return false;
        }
    }

    private int channelPriority(String channelType) {
        if (channelType == null) return 99;
        return switch (channelType) {
            case "ats" -> 0;
            case "company_site" -> 1;
            case "job_board" -> 2;
            case "manual_required" -> 3;
            default -> 99;
        };
    }

    private record PageVerification(boolean ok, String note) {}
}
