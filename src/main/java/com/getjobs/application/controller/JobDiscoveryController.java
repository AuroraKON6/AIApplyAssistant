package com.getjobs.application.controller;

import com.getjobs.application.dto.DiscoveredJob;
import com.getjobs.application.dto.JobDiscoverRequest;
import com.getjobs.application.service.ApplicantProfileService;
import com.getjobs.application.service.CompanyListFileService;
import com.getjobs.application.service.DiagnosticsService;
import com.getjobs.application.service.JobDiscoveryService;
import com.getjobs.application.service.ResumeFileService;
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
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.nio.file.Path;

@Slf4j
@RestController
@RequestMapping("/api/ai-apply")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class JobDiscoveryController {

    private final JobDiscoveryService jobDiscoveryService;
    private final DiagnosticsService diagnosticsService;
    private final ResumeFileService resumeFileService;
    private final ApplicantProfileService applicantProfileService;
    private final CompanyListFileService companyListFileService;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(success("ok"));
    }

    @GetMapping("/diagnostics")
    public ResponseEntity<Map<String, Object>> diagnostics() {
        try {
            return ResponseEntity.ok(success(diagnosticsService.runDiagnostics()));
        } catch (Exception e) {
            log.error("Diagnostics failed", e);
            return ResponseEntity.internalServerError().body(error(e.getMessage()));
        }
    }

    @GetMapping("/readiness")
    public ResponseEntity<Map<String, Object>> readiness() {
        try {
            return ResponseEntity.ok(success(diagnosticsService.runApplicationReadiness()));
        } catch (Exception e) {
            log.error("Readiness check failed", e);
            return ResponseEntity.internalServerError().body(error(e.getMessage()));
        }
    }

    @PostMapping("/resume/upload")
    public ResponseEntity<Map<String, Object>> uploadResume(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(success(resumeFileService.saveResume(file)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Resume upload failed", e);
            return ResponseEntity.internalServerError().body(error(e.getMessage()));
        }
    }

    @PostMapping("/company-list/upload")
    public ResponseEntity<Map<String, Object>> uploadCompanyList(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(success(companyListFileService.parseCompanyList(file)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Company list upload failed", e);
            return ResponseEntity.internalServerError().body(error(e.getMessage()));
        }
    }

    @PostMapping("/resume/extract")
    public ResponseEntity<Map<String, Object>> extractResumeProfile(@RequestBody Map<String, Object> request) {
        try {
            String resumePath = request == null ? "" : String.valueOf(request.getOrDefault("resumePath", "")).trim();
            if (resumePath.isBlank()) {
                throw new IllegalArgumentException("请先上传或填写本地简历文件路径。");
            }
            return ResponseEntity.ok(success(applicantProfileService.extractAndSaveFromResume(Path.of(resumePath))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Resume profile extraction failed", e);
            return ResponseEntity.internalServerError().body(error(e.getMessage()));
        }
    }

    @GetMapping("/profile")
    public ResponseEntity<Map<String, Object>> profile() {
        try {
            return ResponseEntity.ok(success(applicantProfileService.getProfile()));
        } catch (Exception e) {
            log.error("Profile load failed", e);
            return ResponseEntity.internalServerError().body(error(e.getMessage()));
        }
    }

    @PostMapping("/profile")
    public ResponseEntity<Map<String, Object>> saveProfile(@RequestBody Map<String, Object> request) {
        try {
            return ResponseEntity.ok(success(applicantProfileService.saveProfile(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Profile save failed", e);
            return ResponseEntity.internalServerError().body(error(e.getMessage()));
        }
    }

    @PostMapping("/discover")
    public ResponseEntity<Map<String, Object>> discover(@RequestBody JobDiscoverRequest request) {
        try {
            List<DiscoveredJob> jobs = jobDiscoveryService.discover(request);
            return ResponseEntity.ok(success(jobs));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Job discovery failed", e);
            return ResponseEntity.internalServerError().body(error(e.getMessage()));
        }
    }

    @PostMapping("/discover/start")
    public ResponseEntity<Map<String, Object>> startDiscover(@RequestBody JobDiscoverRequest request) {
        try {
            return ResponseEntity.ok(success(jobDiscoveryService.startDiscovery(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to start job discovery", e);
            return ResponseEntity.internalServerError().body(error(e.getMessage()));
        }
    }

    @GetMapping("/discover/status/{id}")
    public ResponseEntity<Map<String, Object>> discoverStatus(@PathVariable String id) {
        try {
            return ResponseEntity.ok(success(jobDiscoveryService.getDiscoveryStatus(id)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to fetch job discovery status", e);
            return ResponseEntity.internalServerError().body(error(e.getMessage()));
        }
    }

    @PostMapping("/discover/cancel/{id}")
    public ResponseEntity<Map<String, Object>> cancelDiscover(@PathVariable String id) {
        try {
            return ResponseEntity.ok(success(jobDiscoveryService.cancelDiscovery(id)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to cancel job discovery", e);
            return ResponseEntity.internalServerError().body(error(e.getMessage()));
        }
    }

    private Map<String, Object> success(Object data) {
        return Map.of("success", true, "data", data);
    }

    private Map<String, Object> error(String message) {
        return Map.of("success", false, "message", message);
    }
}
