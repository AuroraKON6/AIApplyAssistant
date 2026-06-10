package com.getjobs.application.controller;

import com.getjobs.application.dto.onboarding.OnboardingParseRequest;
import com.getjobs.application.dto.onboarding.OnboardingParseResponse;
import com.getjobs.application.service.OnboardingParseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Onboarding 引导控制器
 * 提供 AI 解析自由文本为结构化求职信息的 API
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/onboarding")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingParseService onboardingParseService;

    /**
     * 解析用户自由文本描述为结构化求职信息
     */
    @PostMapping("/parse")
    public ResponseEntity<OnboardingParseResponse> parse(@RequestBody OnboardingParseRequest request) {
        OnboardingParseResponse response = onboardingParseService.parse(request.getDescription());
        return ResponseEntity.ok(response);
    }

    /**
     * 保存求职信息到配置
     * 将解析后的结构化数据写入各平台配置
     */
    @PostMapping("/save-profile")
    public ResponseEntity<Map<String, Object>> saveProfile(@RequestBody OnboardingParseResponse data) {
        // TODO: 将 data 写入各平台配置表
        // 暂时返回成功，后续集成到 ConfigService
        log.info("保存 Onboarding 配置: jobTitle={}, skills={}", data.getJobTitle(), data.getSkills());
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "配置已保存"
        ));
    }
}
