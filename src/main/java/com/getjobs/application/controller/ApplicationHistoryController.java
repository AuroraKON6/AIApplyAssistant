package com.getjobs.application.controller;

import com.getjobs.application.service.ApplicationHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/ai-apply/history")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ApplicationHistoryController {
    private final ApplicationHistoryService historyService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(defaultValue = "80") int limit) {
        try {
            return ResponseEntity.ok(success(historyService.list(limit)));
        } catch (Exception e) {
            log.error("Failed to list application history", e);
            return ResponseEntity.internalServerError().body(error(e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> upsert(@RequestBody Map<String, Object> request) {
        try {
            return ResponseEntity.ok(success(historyService.upsert(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to save application history", e);
            return ResponseEntity.internalServerError().body(error(e.getMessage()));
        }
    }

    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> check(@RequestBody Map<String, Object> request) {
        try {
            Object urlsObj = request == null ? null : request.get("urls");
            List<String> urls = urlsObj instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of();
            return ResponseEntity.ok(success(historyService.check(urls)));
        } catch (Exception e) {
            log.error("Failed to check application history", e);
            return ResponseEntity.internalServerError().body(error(e.getMessage()));
        }
    }

    @DeleteMapping("/{idOrUrl}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String idOrUrl) {
        try {
            return ResponseEntity.ok(success(historyService.delete(idOrUrl)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to delete application history", e);
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
