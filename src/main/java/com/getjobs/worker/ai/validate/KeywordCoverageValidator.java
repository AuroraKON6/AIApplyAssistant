package com.getjobs.worker.ai.validate;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 关键词覆盖率验证器
 */
@Component
public class KeywordCoverageValidator {
    public List<String> covered(String text, List<String> keywords) {
        List<String> used = new ArrayList<>();
        for (String k : keywords) {
            if (k != null && !k.isBlank() && text.contains(k))
                used.add(k);
        }
        return used;
    }
}
