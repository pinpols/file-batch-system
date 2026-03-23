package com.example.batch.worker.dispatchs.runtime;

import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.worker.core.application.TaskDispatchExecutor;
import com.example.batch.worker.core.domain.WorkerExecutionResult;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.dispatchs.config.DispatchWorkerConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchTaskConsumer {

    private final DispatchWorkerLoop workerLoop;
    private final DispatchWorkerConfiguration configuration;
    private final TaskDispatchExecutor taskDispatchExecutor;

    @KafkaListener(topics = "#{__listener.topics()}", groupId = "#{__listener.consumerGroupId()}")
    public void consume(String payload) {
        TaskDispatchMessage message = JsonUtils.fromJson(payload, TaskDispatchMessage.class);
        WorkerRegistration registration = workerLoop.ensureStarted();
        if (!accepts(message, registration)) {
            return;
        }
        WorkerExecutionResult result = taskDispatchExecutor.execute(message, registration.getWorkerId());
        if (result == null) {
            log.info("dispatch task skipped after claim check: taskId={}", message.taskId());
            return;
        }
        log.info("dispatch task processed: taskId={}, success={}, message={}",
                result.taskId(), result.success(), result.message());
    }

    public String[] topics() {
        String configuredWorkerCode = configuration.workerCode();
        if (configuredWorkerCode == null || configuredWorkerCode.isBlank()) {
            return new String[] {configuration.topic()};
        }
        return new String[] {
                configuration.topic(),
                BatchTopics.directDispatchTopic(configuration.topic(), configuredWorkerCode)
        };
    }

    public String consumerGroupId() {
        return configuration.consumerGroupId();
    }

    private boolean accepts(TaskDispatchMessage message, WorkerRegistration registration) {
        return message != null
                && configuration.workerType() != null
                && configuration.workerType().equalsIgnoreCase(message.workerType())
                && (message.selectedWorkerId() == null
                || (registration != null && message.selectedWorkerId().equals(registration.getWorkerId())));
    }
}
