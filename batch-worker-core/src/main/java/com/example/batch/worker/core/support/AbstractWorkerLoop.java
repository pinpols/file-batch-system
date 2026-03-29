package com.example.batch.worker.core.support;

import com.example.batch.worker.core.application.WorkerRuntimeFacade;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.domain.WorkerRegistration;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.util.StringUtils;

/**
 * Worker 生命周期模板（所有 worker 通用骨架）。
 *
 * <p>使用方式：
 * <ul>
 *   <li>子类只提供差异化配置：{@link #workerConfiguration()} / {@link #workerGroup()} / {@link #workerPort()}</li>
 *   <li>子类用一个很薄的 {@code @Scheduled} 方法定期调用 {@link #doHeartbeat()}（避免在抽象类里硬编码配置 key）</li>
 * </ul>
 *
 * <p>该模板负责：
 * <ul>
 *   <li>应用启动后自动注册（{@link #onReady()}）</li>
 *   <li>周期心跳（{@link #doHeartbeat()}）</li>
 *   <li>优雅下线（{@link #onShutdown()}）</li>
 * </ul>
 */
@Slf4j
public abstract class AbstractWorkerLoop {

    private final WorkerRuntimeFacade workerRuntimeFacade;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile WorkerRegistration registration;

    @Value("${batch.worker.registry.fail-fast-on-startup:true}")
    private boolean failFastOnStartup;

    protected AbstractWorkerLoop(WorkerRuntimeFacade workerRuntimeFacade) {
        this.workerRuntimeFacade = workerRuntimeFacade;
    }

    /** Worker 配置（topic、tenantId、workerType 等）。 */
    protected abstract WorkerConfiguration workerConfiguration();

    /** worker 逻辑分组，如 {@code import}/{@code export}/{@code dispatch}。 */
    protected abstract String workerGroup();

    /** worker 对外端口（用于注册元数据；E2E 合并进程时通常为 orchestrator 端口）。 */
    protected abstract int workerPort();

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            ensureStarted();
        } catch (Exception ex) {
            if (failFastOnStartup) {
                throw ex;
            }
            log.error("{} worker register-on-startup failed; will retry on heartbeat. cause={}",
                    workerGroup(), ex.getMessage(), ex);
        }
    }

    /**
     * 由子类的 {@code @Scheduled} 方法调用。
     * <p>把 {@code @Scheduled} 放在子类，是为了避免在抽象层硬编码配置 key（各 worker 的心跳间隔配置可能不同）。
     */
    protected void doHeartbeat() {
        WorkerRegistration current;
        try {
            current = ensureStarted();
        } catch (Exception ex) {
            log.warn("{} worker start/register failed; will retry on next heartbeat. cause={}",
                    workerGroup(), ex.getMessage());
            return;
        }
        try {
            workerRuntimeFacade.heartbeat(current.getWorkerId());
        } catch (Exception ex) {
            log.warn("{} worker heartbeat unavailable: {} ({})",
                    workerGroup(), ex.getMessage(), ex.getClass().getSimpleName());
        }
    }

    /**
     * Idempotent start: registers the worker on first call, returns the registration on
     * subsequent calls.  Thread-safe via double-checked locking.
     */
    public WorkerRegistration ensureStarted() {
        if (started.get()) {
            return registration;
        }
        synchronized (this) {
            if (started.get()) {
                return registration;
            }
            WorkerConfiguration cfg = workerConfiguration();
            WorkerRegistration workerRegistration = new WorkerRegistration();
            workerRegistration.setWorkerId(buildWorkerId(cfg));
            workerRegistration.setTenantId(cfg.tenantId());
            workerRegistration.setWorkerType(cfg.workerType());
            workerRegistration.setWorkerGroup(workerGroup());
            workerRegistration.setHost(resolveHostName());
            workerRegistration.setPort(workerPort());
            workerRegistration.setActive(Boolean.TRUE);
            workerRegistration.setRegisteredAt(OffsetDateTime.now());
            workerRegistration.setLastHeartbeatAt(OffsetDateTime.now());
            registration = workerRuntimeFacade.start(workerRegistration);
            started.set(true);
            log.info("{} worker started: workerId={}, tenantId={}",
                    workerGroup(), registration.getWorkerId(), registration.getTenantId());
            return registration;
        }
    }

    @PreDestroy
    public void shutdown() {
        if (registration != null) {
            try {
                workerRuntimeFacade.shutdown(registration.getWorkerId());
            } catch (Exception ex) {
                log.warn("{} worker shutdown signal failed: {} ({})",
                        workerGroup(), ex.getMessage(), ex.getClass().getSimpleName());
            }
        }
    }

    private String buildWorkerId(WorkerConfiguration cfg) {
        if (StringUtils.hasText(cfg.workerCode())) {
            return cfg.workerCode();
        }
        if (StringUtils.hasText(cfg.workerType())) {
            return cfg.workerType().toLowerCase() + "-" + UUID.randomUUID();
        }
        return workerGroup() + "-" + UUID.randomUUID();
    }

    private String resolveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            return "localhost";
        }
    }
}
