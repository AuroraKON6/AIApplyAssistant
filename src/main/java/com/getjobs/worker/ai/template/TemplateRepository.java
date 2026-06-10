package com.getjobs.worker.ai.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词模板仓库
 * 从 classpath:prompts/*.yml 加载提示词模板并缓存
 */
@Slf4j
@Component
public class TemplateRepository {
    private final Map<String, PromptTemplate> cache = new ConcurrentHashMap<>();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @PostConstruct
    public void load() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:prompts/*.yml");

        log.info("Loading prompt templates from classpath:prompts/*.yml, found {} files", resources.length);

        for (Resource resource : resources) {
            try (InputStream inputStream = resource.getInputStream()) {
                PromptTemplate template = yamlMapper.readValue(inputStream, PromptTemplate.class);
                if (template != null && template.getId() != null) {
                    cache.put(template.getId(), template);
                    log.info("Loaded prompt template: id={}, description={}, segments={}",
                            template.getId(), template.getDescription(),
                            template.getSegments() != null ? template.getSegments().size() : 0);
                } else {
                    log.warn("Skipping invalid template from {}: missing id", resource.getFilename());
                }
            } catch (Exception e) {
                log.error("Failed to load template from {}: {}", resource.getFilename(), e.getMessage(), e);
                throw new IOException("Failed to load template from " + resource.getFilename(), e);
            }
        }

        log.info("Successfully loaded {} prompt templates", cache.size());
    }

    /**
     * 根据 ID 获取提示词模板
     */
    public PromptTemplate get(String id) {
        return Optional.ofNullable(cache.get(id))
                .orElseThrow(() -> new IllegalArgumentException("Template not found: " + id));
    }

    /**
     * 检查模板是否存在
     */
    public boolean exists(String id) {
        return cache.containsKey(id);
    }
}
