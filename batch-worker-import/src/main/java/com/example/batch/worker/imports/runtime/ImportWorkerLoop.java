package com.example.batch.worker.imports.runtime;

import com.example.batch.worker.core.application.WorkerRuntimeFacade;
import com.example.batch.worker.core.domain.WorkerRegistration;
import jakarta.annotation.PreDestroy;
import com.example.batch.worker.imports.config.ImportWorkerConfiguration;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportWorkerLoop {

    private final WorkerRuntimeFacade workerRuntimeFacade;
    private final ImportWorkerConfiguration configuration;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile WorkerRegistration registration;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        ensureStarted();
    }

    @Scheduled(fixedDelayString = "${batch.worker.import.heartbeat-interval-millis:15000}")
    public void heartbeat() {
        WorkerRegistration current = ensureStarted();
        if (current == null) {
            return;
        }
        try {
            workerRuntimeFacade.heartbeat(current.getWorkerId());
        } catch (Exception ex) {
            log.warn("import worker heartbeat failed: {}", ex.getMessage(), ex);
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
            workerRegistration.setWorkerGroup("import");
            workerRegistration.setHost(resolveHostName());
            workerRegistration.setPort(8083);
            workerRegistration.setActive(Boolean.TRUE);
            workerRegistration.setRegisteredAt(OffsetDateTime.now());
            workerRegistration.setLastHeartbeatAt(OffsetDateTime.now());
            registration = workerRuntimeFacade.start(workerRegistration);
            started.set(true);
            log.info("import worker started: workerId={}, tenantId={}", registration.getWorkerId(), registration.getTenantId());
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
        return "import-" + UUID.randomUUID();
    }

    private String resolveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            return "localhost";
        }
    }
}
