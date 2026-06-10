package com.getjobs.worker.ai.validate;

import org.springframework.stereotype.Component;

/**
 * 文本长度验证器
 */
@Component
public class LengthValidator {
    public void check(String text, int maxChars) {
        if (text.codePointCount(0, text.length()) > maxChars) {
            throw new IllegalArgumentException("Exceed max chars: " + text.codePointCount(0, text.length()) + " > " + maxChars);
        }
    }
}
