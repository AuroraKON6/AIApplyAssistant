package com.getjobs.worker.ai.validate;

import lombok.Data;

import java.util.List;

/**
 * AI 输出约束定义
 */
@Data
public class OutputContract {
    private int maxChars;
    private List<String> mustCoverKeywords;
    private boolean jsonMode;
    private String jsonSchema;
}
