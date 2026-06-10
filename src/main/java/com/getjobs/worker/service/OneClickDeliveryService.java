package com.getjobs.worker.service;

import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.utils.RecruitmentPlatformEnum;
import com.getjobs.worker.utils.TaskExecutionStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 一键投递服务
 * 协调多个平台的投递任务，支持顺序执行和独立停止
 *
 * @author system
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OneClickDeliveryService {

    private final BossJobService bossJobService;
    private final Job51JobService job51JobService;
    private final ZhilianJobService zhilianJobService;
    private final LiepinJobService liepinJobService;
    private final TaskExecutionManager taskExecutionManager;

    private volatile boolean isRunning = false;
    private volatile boolean shouldStop = false;

    /**
     * 获取所有平台服务
     */
    private Map<RecruitmentPlatformEnum, JobPlatformService> getPlatformServices() {
        Map<RecruitmentPlatformEnum, JobPlatformService> services = new LinkedHashMap<>();
        services.put(RecruitmentPlatformEnum.BOSS_ZHIPIN, bossJobService);
        services.put(RecruitmentPlatformEnum.JOB_51, job51JobService);
        services.put(RecruitmentPlatformEnum.ZHILIAN_ZHAOPIN, zhilianJobService);
        services.put(RecruitmentPlatformEnum.LIEPIN, liepinJobService);
        return services;
    }

    /**
     * 执行一键投递（所有平台顺序执行）
     *
     * @param platforms        要投递的平台列表，为空则投递所有平台
     * @param progressCallback 进度回调
     */
    public void executeDelivery(List<RecruitmentPlatformEnum> platforms,
                                Consumer<JobProgressMessage> progressCallback) {
        if (isRunning) {
            progressCallback.accept(JobProgressMessage.warning("all", "一键投递任务已在运行中"));
            return;
        }

        isRunning = true;
        shouldStop = false;

        Map<RecruitmentPlatformEnum, JobPlatformService> allServices = getPlatformServices();
        List<RecruitmentPlatformEnum> targetPlatforms = platforms != null && !platforms.isEmpty()
                ? platforms
                : new ArrayList<>(allServices.keySet());

        progressCallback.accept(JobProgressMessage.info("all",
                String.format("一键投递开始，目标平台: %d 个", targetPlatforms.size())));

        int totalDelivered = 0;
        int successPlatforms = 0;

        for (RecruitmentPlatformEnum platform : targetPlatforms) {
            if (shouldStop) {
                progressCallback.accept(JobProgressMessage.warning("all", "用户取消，停止一键投递"));
                break;
            }

            JobPlatformService service = allServices.get(platform);
            if (service == null) {
                progressCallback.accept(JobProgressMessage.warning("all",
                        "平台 " + platform.getPlatformName() + " 服务未找到，跳过"));
                continue;
            }

            // 检查平台是否正在运行
            if (service.isRunning()) {
                progressCallback.accept(JobProgressMessage.warning(platform.getPlatformCode(),
                        platform.getPlatformName() + " 任务已在运行中，跳过"));
                continue;
            }

            progressCallback.accept(JobProgressMessage.info(platform.getPlatformCode(),
                    "开始投递 " + platform.getPlatformName() + "..."));

            // 记录任务开始
            taskExecutionManager.startTask(platform);
            taskExecutionManager.updateTaskStep(platform, TaskExecutionStep.LOGIN_CHECK, "检查登录状态");

            try {
                // 使用CompletableFuture等待单平台投递完成
                CompletableFuture<Integer> future = new CompletableFuture<>();

                service.executeDelivery(message -> {
                    progressCallback.accept(message);
                    // 从成功消息中提取投递数量
                    if ("success".equals(message.getType())) {
                        try {
                            String msg = message.getMessage();
                            int count = extractDeliveryCount(msg);
                            future.complete(count);
                        } catch (Exception e) {
                            future.complete(0);
                        }
                    } else if ("error".equals(message.getType())) {
                        future.completeExceptionally(new RuntimeException(message.getMessage()));
                    }
                });

                // 等待完成（设置超时）
                Integer count = future.get();
                totalDelivered += count;
                successPlatforms++;
                taskExecutionManager.completeTask(platform, true);

            } catch (Exception e) {
                log.error("{} 投递失败: {}", platform.getPlatformName(), e.getMessage());
                progressCallback.accept(JobProgressMessage.error(platform.getPlatformCode(),
                        platform.getPlatformName() + " 投递失败: " + e.getMessage()));
                taskExecutionManager.completeTask(platform, false);
            }

            // 平台间间隔
            if (!shouldStop) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        isRunning = false;
        shouldStop = false;

        progressCallback.accept(JobProgressMessage.info("all",
                String.format("一键投递完成: %d/%d 个平台成功, 共 %d 个投递",
                        successPlatforms, targetPlatforms.size(), totalDelivered)));
    }

    /**
     * 停止一键投递
     */
    public void stopDelivery() {
        if (!isRunning) {
            log.warn("一键投递任务未在运行，无需停止");
            return;
        }
        log.info("收到停止一键投递请求");
        shouldStop = true;

        // 同时停止所有正在运行的平台任务
        bossJobService.stopDelivery();
        job51JobService.stopDelivery();
        zhilianJobService.stopDelivery();
        liepinJobService.stopDelivery();
    }

    /**
     * 获取一键投递状态
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("isRunning", isRunning);

        // 收集各平台状态
        Map<String, Object> platforms = new HashMap<>();
        platforms.put("boss", bossJobService.getStatus());
        platforms.put("51job", job51JobService.getStatus());
        platforms.put("zhilian", zhilianJobService.getStatus());
        platforms.put("liepin", liepinJobService.getStatus());
        status.put("platforms", platforms);

        // 收集任务执行状态
        Map<String, Object> taskStatuses = new HashMap<>();
        taskExecutionManager.getAllTaskStatus().forEach((platform, taskStatus) -> {
            Map<String, Object> ts = new HashMap<>();
            ts.put("step", taskStatus.getCurrentStep().getDescription());
            ts.put("stepDescription", taskStatus.getStepDescription());
            ts.put("isTerminated", taskStatus.isTerminated());
            taskStatuses.put(platform.getPlatformCode(), ts);
        });
        status.put("taskStatuses", taskStatuses);

        return status;
    }

    public boolean isRunning() {
        return isRunning;
    }

    /**
     * 从成功消息中提取投递数量
     */
    private int extractDeliveryCount(String message) {
        try {
            // 匹配 "共投递XX个" 或 "共发起XX个聊天"
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(\\d+)个(?:职位|聊天)")
                    .matcher(message);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        } catch (Exception e) {
            log.debug("解析投递数量失败: {}", message);
        }
        return 0;
    }
}
