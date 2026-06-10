package com.getjobs.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.dto.onboarding.OnboardingParseResponse;
import com.getjobs.worker.ai.template.PromptRenderer;
import com.getjobs.worker.ai.template.PromptTemplate;
import com.getjobs.worker.ai.template.TemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Onboarding 解析服务
 * 将用户自由文本描述解析为结构化的求职信息
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingParseService {

    private static final String DEFAULT_TEMPLATE_ID = "onboarding-parse-v1";
    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");

    private final TemplateRepository templateRepository;
    private final PromptRenderer promptRenderer;
    private final AiService aiService;
    private final ObjectMapper objectMapper;

    /**
     * 解析用户描述为结构化求职信息
     */
    public OnboardingParseResponse parse(String description) {
        // 1. 加载模板
        PromptTemplate template = templateRepository.get(DEFAULT_TEMPLATE_ID);

        // 2. 渲染模板
        Map<String, Object> vars = new HashMap<>();
        vars.put("description", description);
        String prompt = promptRenderer.renderTemplate(template, vars);

        // 3. 调用 AI
        String raw = aiService.sendRequest(prompt).trim();
        log.debug("[Onboarding] AI raw response: {}", raw);

        // 4. 解析 JSON 响应
        return parseJson(raw);
    }

    /**
     * 解析 AI 返回的 JSON
     */
    private OnboardingParseResponse parseJson(String raw) {
        // 优先提取 markdown 代码块中的 JSON
        Matcher matcher = JSON_BLOCK.matcher(raw);
        String json = matcher.find() ? matcher.group(1).trim() : raw;

        try {
            return objectMapper.readValue(json, OnboardingParseResponse.class);
        } catch (Exception e) {
            log.error("[Onboarding] Failed to parse AI response as JSON: {}", raw, e);
            throw new IllegalStateException("AI 返回格式异常，无法解析求职信息", e);
        }
    }
}
