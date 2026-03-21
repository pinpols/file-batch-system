package com.example.batch.worker.exports.runtime;

import com.example.batch.worker.core.app.WorkerRuntimeFacade;
import com.example.batch.worker.core.domain.WorkerRegistration;
import jakarta.annotation.PreDestroy;
import com.example.batch.worker.exports.config.ExportWorkerConfiguration;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class ExportWorkerLoop {

    private final WorkerRuntimeFacade workerRuntimeFacade;
    private final ExportWorkerConfiguration configuration;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile WorkerRegistration registration;

    public ExportWorkerLoop(WorkerRuntimeFacade workerRuntimeFacade,
                            ExportWorkerConfiguration configuration) {
        this.workerRuntimeFacade = workerRuntimeFacade;
        this.configuration = configuration;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        ensureStarted();
    }

    @Scheduled(fixedDelayString = "${batch.worker.export.heartbeat-interval-millis:15000}")
    public void heartbeat() {
        WorkerRegistration current = ensureStarted();
        if (current == null) {
            return;
        }
        try {
            workerRuntimeFacade.heartbeat(current.getWorkerId());
        } catch (Exception ex) {
            log.warn("export worker heartbeat failed: {}", ex.getMessage(), ex);
        }
    }

    public WorkerRegistration ensureStarted() {
        if (started.get()) {
            return registration;
        }
        synchronized (this) {
            if (started.get()) {
                return registration;
            }
            WorkerRegistration workerRegistration = new WorkerRegistration();
            workerRegistration.setWorkerId(buildWorkerId());
            workerRegistration.setTenantId(configuration.tenantId());
            workerRegistration.setWorkerType(configuration.workerType());
            workerRegistration.setWorkerGroup("export");
            workerRegistration.setHost(resolveHostName());
            workerRegistration.setPort(8084);
            workerRegistration.setActive(Boolean.TRUE);
            workerRegistration.setRegisteredAt(OffsetDateTime.now());
            workerRegistration.setLastHeartbeatAt(OffsetDateTime.now());
            registration = workerRuntimeFacade.start(workerRegistration);
            started.set(true);
            log.info("export worker started: workerId={}, tenantId={}", registration.getWorkerId(), registration.getTenantId());
            return registration;
        }
    }

    @PreDestroy
    public void shutdown() {
        if (registration != null) {
            workerRuntimeFacade.shutdown(registration.getWorkerId());
        }
    }

    private String buildWorkerId() {
        if (StringUtils.hasText(configuration.workerCode())) {
            return configuration.workerCode();
        }
        if (StringUtils.hasText(configuration.workerType())) {
            return configuration.workerType().toLowerCase() + "-" + UUID.randomUUID();
        }
        return "export-" + UUID.randomUUID();
    }

    private String resolveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            return "localhost";
        }
    }
}
