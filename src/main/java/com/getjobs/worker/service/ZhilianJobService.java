package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.zhilian.ZhiLian;
import com.getjobs.worker.zhilian.ZhilianConfig;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 智联招聘任务服务
 * 管理智联招聘平台的投递任务执行和状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZhilianJobService implements JobPlatformService {
    private static final String PLATFORM = "zhilian";

    private final PlaywrightManager playwrightManager;
    private final ObjectProvider<ZhiLian> zhilianProvider;
    private final ConfigService configService;

    // 任务运行状态
    private volatile boolean isRunning = false;
    // 停止标志
    private volatile boolean shouldStop = false;
    // 登录失败标志
    private volatile boolean loginRequired = false;

    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        executeDelivery(null, null, false, progressCallback);
    }

    /**
     * 执行投递任务（支持从AI Apply页面传入关键词和公司名）
     * @param keywordOverride 覆盖配置中的关键词（来自用户求职目标）
     * @param companyNameOverride 目标公司名（用于在详情页匹配）
     * @param autoSubmit 是否自动提交（false则在提交前暂停）
     * @param progressCallback 进度回调
     */
    public void executeDelivery(String keywordOverride, String companyNameOverride,
                                boolean autoSubmit, Consumer<JobProgressMessage> progressCallback) {
        if (isRunning) {
            progressCallback.accept(JobProgressMessage.warning(PLATFORM, "任务已在运行中"));
            return;
        }

        try {
            // 获取智联招聘页面实例
            Page page = playwrightManager.getZhilianPage();
            if (page == null) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "智联招聘页面未初始化"));
                return;
            }

            // 检查是否已登录
            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "请先登录智联招聘"));
                return;
            }

            // 通过校验后再标记运行
            isRunning = true;
            shouldStop = false;

            // 暂停后台登录监控，避免与投递流程并发访问同一Page
            playwrightManager.pauseZhilianMonitoring();

            // 加载配置（统一从 zhilian_config 专表读取）
            ZhilianConfig config = configService.getZhilianConfig();

            // 如果从AI Apply页面传入了关键词，覆盖配置中的关键词
            if (keywordOverride != null && !keywordOverride.isBlank()) {
                config.setKeywords(java.util.List.of(keywordOverride));
                log.info("使用AI Apply传入的关键词: {}", keywordOverride);
            }

            progressCallback.accept(JobProgressMessage.info(PLATFORM, "配置加载成功"));
            if (companyNameOverride != null && !companyNameOverride.isBlank()) {
                progressCallback.accept(JobProgressMessage.info(PLATFORM, "目标公司: " + companyNameOverride));
            }
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "自动提交: " + (autoSubmit ? "是" : "否")));

            progressCallback.accept(JobProgressMessage.info(PLATFORM, "开始投递任务..."));

            // 创建ZhiLian实例并执行投递
            ZhiLian.ProgressCallback zhilianCallback = (message, current, total) -> {
                if (current != null && total != null) {
                    progressCallback.accept(JobProgressMessage.progress(PLATFORM, message, current, total));
                } else {
                    progressCallback.accept(JobProgressMessage.info(PLATFORM, message));
                }
            };

            ZhiLian zhilian = zhilianProvider.getObject();
            zhilian.setPage(page);
            zhilian.setConfig(config);
            zhilian.setProgressCallback(zhilianCallback);
            zhilian.setShouldStopCallback(this::shouldStop);
            zhilian.setCompanyNameFilter(companyNameOverride);
            zhilian.setAutoSubmit(autoSubmit);
            zhilian.prepare();

            int deliveredCount = zhilian.execute();

            // Check if login was required
            if (zhilian.isLoginRequired()) {
                loginRequired = true;
                progressCallback.accept(JobProgressMessage.error(PLATFORM,
                    "智联招聘需要先登录，请在打开的浏览器中完成登录后再继续投递"));
            } else {
                loginRequired = false;
                progressCallback.accept(JobProgressMessage.success(PLATFORM,
                    String.format("投递任务完成，共投递%d个职位", deliveredCount)));
            }
        } catch (Exception e) {
            log.error("智联招聘投递任务执行失败", e);
            progressCallback.accept(JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
        } finally {
            isRunning = false;
            shouldStop = false;
            // 恢复后台登录监控
            try {
                playwrightManager.resumeZhilianMonitoring();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void stopDelivery() {
        if (isRunning) {
            log.info("收到停止智联招聘投递任务的请求");
            shouldStop = true;
        }
    }

    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        status.put("isRunning", isRunning);
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        status.put("loginRequired", loginRequired);
        return status;
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * 检查是否应该停止
     */
    public boolean shouldStop() {
        return shouldStop;
    }

    
}
