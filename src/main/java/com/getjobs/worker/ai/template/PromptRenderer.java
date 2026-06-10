package com.getjobs.worker.ai.template;

import com.samskivert.mustache.Mustache;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Mustache 模板渲染器
 * 将 PromptTemplate 中的 segments 渲染为完整的 prompt 文本
 */
@Component
public class PromptRenderer {
    private final Mustache.Compiler compiler = Mustache.compiler();

    /**
     * 渲染单个 segment 的 content
     */
    public String render(String raw, Map<String, Object> vars) {
        return compiler.defaultValue("").compile(raw).execute(vars);
    }

    /**
     * 渲染整个模板，拼接所有 segments
     */
    public String renderTemplate(PromptTemplate template, Map<String, Object> vars) {
        StringBuilder sb = new StringBuilder();
        if (template.getSegments() != null) {
            for (PromptTemplate.Segment segment : template.getSegments()) {
                String rendered = render(segment.getContent(), vars);
                sb.append(rendered).append("\n\n");
            }
        }
        return sb.toString().trim();
    }
}
