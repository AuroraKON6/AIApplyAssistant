package com.getjobs.application.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class WebSearchService {

    public record SearchResult(String title, String url, String snippet) {}

    private static final Pattern DDG_LINK = Pattern.compile(
            "<a[^>]+rel=\"nofollow\"[^>]+href=\"([^\"]+)\"[^>]*>([^<]*)</a>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DDG_SNIPPET = Pattern.compile(
            "<td[^>]*class=\"result-snippet\"[^>]*>(.*?)</td>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern BING_LINK = Pattern.compile(
            "<h2[^>]*><a[^>]+href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a></h2>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern BING_SNIPPET = Pattern.compile(
            "<p[^>]*class=\"[^\"]*b_lineclamp[^\"]*\"[^>]*>(.*?)</p>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern TAG_STRIP = Pattern.compile("<[^>]+>");

    private static final Set<String> JOB_BOARD_DOMAINS = Set.of(
            "zhipin.com", "liepin.com", "51job.com", "zhaopin.com",
            "linkedin.com", "indeed.com", "glassdoor.com",
            "greenhouse.io", "lever.co", "workday.com", "ashbyhq.com", "smartrecruiters.com",
            "lagou.com", "boss.com", "recruit.net", "careerbuilder.com", "monster.com"
    );

    private volatile HttpClient httpClient;

    public List<SearchResult> search(String query, int maxResults) {
        // Try Bing first (more reliable in China)
        List<SearchResult> results = searchBing(query, maxResults);
        if (results.isEmpty()) {
            log.info("Bing returned no results for '{}', trying DuckDuckGo", query);
            results = searchDuckDuckGo(query, maxResults);
        }
        return results;
    }

    private List<SearchResult> searchDuckDuckGo(String query, int maxResults) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://lite.duckduckgo.com/lite/?q=" + encoded + "&kl=cn-zh";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("DuckDuckGo returned HTTP {}", response.statusCode());
                return List.of();
            }
            return parseDuckDuckGo(response.body(), maxResults);
        } catch (Exception e) {
            log.warn("DuckDuckGo search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<SearchResult> parseDuckDuckGo(String html, int maxResults) {
        List<SearchResult> results = new ArrayList<>();
        Matcher linkMatcher = DDG_LINK.matcher(html);
        List<String[]> links = new ArrayList<>();
        while (linkMatcher.find()) {
            links.add(new String[]{linkMatcher.group(2).trim(), linkMatcher.group(1).trim()});
        }

        List<String> snippets = new ArrayList<>();
        Matcher snippetMatcher = DDG_SNIPPET.matcher(html);
        while (snippetMatcher.find()) {
            snippets.add(stripTags(snippetMatcher.group(1)).trim());
        }

        for (int i = 0; i < links.size() && results.size() < maxResults; i++) {
            String[] link = links.get(i);
            String title = link[0];
            String resultUrl = link[1];
            if (resultUrl.startsWith("http") && !resultUrl.contains("duckduckgo.com")) {
                String snippet = i < snippets.size() ? snippets.get(i) : "";
                results.add(new SearchResult(title, resultUrl, snippet));
            }
        }
        return results;
    }

    private List<SearchResult> searchBing(String query, int maxResults) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://cn.bing.com/search?q=" + encoded + "&setlang=zh-CN";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("Bing returned HTTP {}", response.statusCode());
                return List.of();
            }
            return parseBing(response.body(), maxResults);
        } catch (Exception e) {
            log.warn("Bing search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<SearchResult> parseBing(String html, int maxResults) {
        List<SearchResult> allResults = new ArrayList<>();
        Matcher linkMatcher = BING_LINK.matcher(html);
        List<String[]> links = new ArrayList<>();
        while (linkMatcher.find()) {
            links.add(new String[]{stripTags(linkMatcher.group(2)).trim(), linkMatcher.group(1).trim()});
        }

        List<String> snippets = new ArrayList<>();
        Matcher snippetMatcher = BING_SNIPPET.matcher(html);
        while (snippetMatcher.find()) {
            snippets.add(stripTags(snippetMatcher.group(1)).trim());
        }

        log.debug("Bing raw links found: {}", links.size());
        for (int i = 0; i < links.size(); i++) {
            String[] link = links.get(i);
            String snippet = i < snippets.size() ? snippets.get(i) : "";
            if (!link[1].contains("bing.com") && link[1].startsWith("http")) {
                log.debug("Bing result {}: url={}", i + 1, link[1]);
                allResults.add(new SearchResult(link[0], link[1], snippet));
            }
        }

        // Prioritize job board results
        List<SearchResult> jobBoardResults = new ArrayList<>();
        List<SearchResult> otherResults = new ArrayList<>();
        for (SearchResult r : allResults) {
            if (isJobBoardUrl(r.url())) {
                jobBoardResults.add(r);
            } else {
                otherResults.add(r);
            }
        }

        List<SearchResult> results = new ArrayList<>();
        results.addAll(jobBoardResults);
        results.addAll(otherResults);

        if (results.size() > maxResults) {
            results = results.subList(0, maxResults);
        }

        log.info("Bing parsed: {} job board results, {} other results, returning {}",
                jobBoardResults.size(), otherResults.size(), results.size());
        return results;
    }

    private boolean isJobBoardUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        for (String domain : JOB_BOARD_DOMAINS) {
            if (lower.contains(domain)) {
                return true;
            }
        }
        return false;
    }

    private String stripTags(String html) {
        return TAG_STRIP.matcher(html).replaceAll("").replaceAll("&amp;", "&").replaceAll("&lt;", "<").replaceAll("&gt;", ">").replaceAll("&quot;", "\"").replaceAll("&#39;", "'").replaceAll("\\s+", " ").trim();
    }

    private HttpClient httpClient() {
        HttpClient client = httpClient;
        if (client != null) {
            return client;
        }
        synchronized (this) {
            if (httpClient == null) {
                httpClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
            }
            return httpClient;
        }
    }
}
