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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.util.StringUtils;

/**
 * Shared lifecycle template for all worker types.
 *
 * <p>Subclasses provide the three differentiating values ({@link #workerConfiguration()},
 * {@link #workerGroup()}, {@link #workerPort()}) and a thin {@code @Scheduled} method that
 * calls {@link #doHeartbeat()}.  All registration, heartbeat, and shutdown logic lives here.
 */
@Slf4j
public abstract class AbstractWorkerLoop {

    private final WorkerRuntimeFacade workerRuntimeFacade;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile WorkerRegistration registration;

    protected AbstractWorkerLoop(WorkerRuntimeFacade workerRuntimeFacade) {
        this.workerRuntimeFacade = workerRuntimeFacade;
    }

    /** Worker-specific configuration (topic, tenantId, workerType, etc.). */
    protected abstract WorkerConfiguration workerConfiguration();

    /** Logical group name, e.g. {@code "import"}, {@code "export"}, {@code "dispatch"}. */
    protected abstract String workerGroup();

    /** HTTP port this worker process listens on (used for registration metadata). */
    protected abstract int workerPort();

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        ensureStarted();
    }

    /**
     * Called by each subclass's {@code @Scheduled} method.
     * Keeping {@code @Scheduled} in the subclass avoids hard-coding the property key here.
     */
    protected void doHeartbeat() {
        WorkerRegistration current = ensureStarted();
        if (current == null) {
            return;
        }
        try {
            workerRuntimeFacade.heartbeat(current.getWorkerId());
        } catch (Exception ex) {
            log.warn("{} worker heartbeat failed: {}", workerGroup(), ex.getMessage(), ex);
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
            workerRuntimeFacade.shutdown(registration.getWorkerId());
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
