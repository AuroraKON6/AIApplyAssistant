package com.getjobs.application.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.service.OneClickDeliveryService;
import com.getjobs.worker.utils.RecruitmentPlatformEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 一键投递控制器
 * 提供跨平台一键投递的API接口
 *
 * @author system
 */
@Slf4j
@RestController
@RequestMapping("/api/delivery")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class OneClickDeliveryController {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final OneClickDeliveryService oneClickDeliveryService;

    private final List<SseEmitter> progressEmitters = new CopyOnWriteArrayList<>();

    /** SSE - 一键投递进度推送 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress() {
        SseEmitter emitter = new SseEmitter(0L);
        progressEmitters.add(emitter);

        emitter.onCompletion(() -> progressEmitters.remove(emitter));
        emitter.onTimeout(() -> progressEmitters.remove(emitter));
        emitter.onError(e -> progressEmitters.remove(emitter));

        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("message", "已连接到一键投递进度推送")));
        } catch (IOException e) {
            log.error("发送SSE连接消息失败", e);
        }
        return emitter;
    }

    /** POST - 启动一键投递（所有平台） */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startAll() {
        return startDelivery(null);
    }

    /** POST - 启动指定平台投递 */
    @PostMapping("/start/{platform}")
    public ResponseEntity<Map<String, Object>> startPlatform(@PathVariable String platform) {
        RecruitmentPlatformEnum platformEnum = RecruitmentPlatformEnum.getByCode(platform);
        if (platformEnum == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "不支持的平台: " + platform
            ));
        }
        return startDelivery(List.of(platformEnum));
    }

    /** POST - 启动投递（内部方法） */
    private ResponseEntity<Map<String, Object>> startDelivery(List<RecruitmentPlatformEnum> platforms) {
        Map<String, Object> response = new HashMap<>();

        if (oneClickDeliveryService.isRunning()) {
            response.put("success", false);
            response.put("message", "投递任务已在运行中");
            return ResponseEntity.status(409).body(response);
        }

        // 异步执行投递
        CompletableFuture.runAsync(() -> {
            oneClickDeliveryService.executeDelivery(platforms, this::sendProgress);
        });

        response.put("success", true);
        response.put("message", platforms == null ? "一键投递已启动" : "平台投递已启动");
        return ResponseEntity.ok(response);
    }

    /** POST - 停止一键投递 */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopAll() {
        Map<String, Object> response = new HashMap<>();

        if (!oneClickDeliveryService.isRunning()) {
            response.put("success", false);
            response.put("message", "没有正在运行的投递任务");
            return ResponseEntity.badRequest().body(response);
        }

        oneClickDeliveryService.stopDelivery();
        response.put("success", true);
        response.put("message", "停止请求已发送");
        return ResponseEntity.ok(response);
    }

    /** GET - 获取一键投递状态 */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(oneClickDeliveryService.getStatus());
    }

    private void sendProgress(JobProgressMessage message) {
        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : progressEmitters) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(objectMapper.writeValueAsString(message)));
            } catch (Exception e) {
                if (isClientDisconnected(e)) {
                    deadEmitters.add(emitter);
                    try { emitter.complete(); } catch (Exception ignored) {}
                } else {
                    log.error("发送进度消息失败", e);
                    deadEmitters.add(emitter);
                }
            }
        }
        progressEmitters.removeAll(deadEmitters);
    }

    @Scheduled(fixedRate = 30000)
    public void heartbeat() {
        if (progressEmitters.isEmpty()) return;
        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : progressEmitters) {
            try {
                emitter.send(SseEmitter.event().name("ping").data("keep-alive"));
            } catch (Exception e) {
                if (isClientDisconnected(e)) {
                    deadEmitters.add(emitter);
                    try { emitter.complete(); } catch (Exception ignored) {}
                } else {
                    log.error("发送心跳失败", e);
                    deadEmitters.add(emitter);
                }
            }
        }
        progressEmitters.removeAll(deadEmitters);
    }

    private boolean isClientDisconnected(Exception e) {
        return e instanceof AsyncRequestNotUsableException
                || e instanceof ClientAbortException
                || (e.getCause() instanceof ClientAbortException)
                || (e instanceof IOException && e.getMessage() != null && e.getMessage().contains("中止了一个已建立的连接"));
    }
}
