package com.getjobs.application.controller;

import com.getjobs.application.dto.SkyvernApplyRequest;
import com.getjobs.application.dto.SkyvernRunRequest;
import com.getjobs.application.service.SkyvernService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/skyvern")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class SkyvernController {
    private final SkyvernService skyvernService;

    @GetMapping("/defaults")
    public ResponseEntity<Map<String, Object>> defaults() {
        return ResponseEntity.ok(success(skyvernService.defaults()));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health(
            @RequestParam(required = false) String skyvernBaseUrl,
            @RequestParam(required = false) String skyvernApiKey
    ) {
        return ResponseEntity.ok(success(skyvernService.health(skyvernBaseUrl, skyvernApiKey)));
    }

    @GetMapping("/llm-config")
    public ResponseEntity<Map<String, Object>> llmConfig() {
        return ResponseEntity.ok(success(skyvernService.llmConfig()));
    }

    @PostMapping("/llm-config")
    public ResponseEntity<Map<String, Object>> saveLlmConfig(@RequestBody Map<String, Object> request) {
        try {
            return ResponseEntity.ok(success(skyvernService.saveLlmConfig(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to save Skyvern LLM config", e);
            return ResponseEntity.internalServerError().body(error(e.getMessage()));
        }
    }

    @PostMapping("/apply")
    public ResponseEntity<Map<String, Object>> apply(@RequestBody SkyvernApplyRequest request) {
        try {
            return ResponseEntity.ok(success(skyvernService.startApplication(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to start Skyvern job application task", e);
            return ResponseEntity.internalServerError().body(error(e.getMessage()));
        }
    }

    @PostMapping("/runs/status")
    public ResponseEntity<Map<String, Object>> runStatus(@RequestBody SkyvernRunRequest request) {
        try {
            return ResponseEntity.ok(success(skyvernService.getRun(
                    request.runId(),
                    request.skyvernBaseUrl(),
                    request.skyvernApiKey()
            )));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to fetch Skyvern run status", e);
            return ResponseEntity.internalServerError().body(error(e.getMessage()));
        }
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<Map<String, Object>> runStatusByPath(@PathVariable String runId) {
        try {
            return ResponseEntity.ok(success(skyvernService.getRun(runId, null, null)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to fetch Skyvern run status", e);
            return ResponseEntity.internalServerError().body(error(e.getMessage()));
        }
    }

    @PostMapping("/runs/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@RequestBody SkyvernRunRequest request) {
        try {
            return ResponseEntity.ok(success(skyvernService.cancelRun(
                    request.runId(),
                    request.skyvernBaseUrl(),
                    request.skyvernApiKey()
            )));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to cancel Skyvern run", e);
            return ResponseEntity.internalServerError().body(error(e.getMessage()));
        }
    }

    private Map<String, Object> success(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return response;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }
}
