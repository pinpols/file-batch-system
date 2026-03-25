package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.support.WorkerSelfRegistrationService;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        // Best-effort: mark workers draining before Kafka containers stop fetching.
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
    }
}

