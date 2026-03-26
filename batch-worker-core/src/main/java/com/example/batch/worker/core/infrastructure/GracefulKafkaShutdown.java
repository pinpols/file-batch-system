package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.support.WorkerSelfRegistrationService;
import java.time.Duration;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GracefulKafkaShutdown implements ApplicationListener<ContextClosedEvent> {

    private final WorkerRuntimeState workerRuntimeState;
    private final WorkerSelfRegistrationService workerRegistryService;
    private final KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;
    private final ActiveTaskLeaseRegistry activeTaskLeaseRegistry;

    @Value("${batch.worker.graceful-shutdown.timeout-seconds:120}")
    private long gracefulShutdownTimeoutSeconds;

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        // 关闭顺序的意图：
        // 1) 先把 worker 标记为 DRAINING（best-effort），让 orchestrator 在调度层面减少派发
        // 2) 再停止 Kafka listener，避免继续拉取新任务
        // 3) 最后等待 in-flight 任务自然结束（有超时），避免 RUNNING 卡死/重复调度
        Collection<WorkerRegistration> registrations = workerRuntimeState.snapshot();
        for (WorkerRegistration registration : registrations) {
            if (registration == null || registration.getWorkerId() == null || registration.getWorkerId().isBlank()) {
                continue;
            }
            try {
                workerRegistryService.updateStatus(registration, WorkerRegistryStatus.DRAINING.code());
            } catch (Exception ex) {
                log.warn("failed to mark worker draining on shutdown: workerId={}, cause={}",
                        registration.getWorkerId(), ex.getMessage());
            }
        }

        try {
            kafkaListenerEndpointRegistry.stop();
            log.info("kafka listener containers stopped");
        } catch (Exception ex) {
            log.warn("failed to stop kafka listener containers: {}", ex.getMessage(), ex);
        }

        try {
            activeTaskLeaseRegistry.awaitDrain(Duration.ofSeconds(Math.max(0L, gracefulShutdownTimeoutSeconds)));
        } catch (Exception ex) {
            log.warn("failed to await in-flight tasks drain: {}", ex.getMessage(), ex);
        }
    }
}

