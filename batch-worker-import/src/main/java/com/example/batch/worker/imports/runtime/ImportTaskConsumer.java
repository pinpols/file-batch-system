package com.example.batch.worker.imports.runtime;

import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.worker.core.app.TaskDispatchExecutor;
import com.example.batch.worker.core.domain.WorkerExecutionResult;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.imports.config.ImportWorkerConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportTaskConsumer {

    private final ImportWorkerLoop workerLoop;
    private final ImportWorkerConfiguration configuration;
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
            log.info("import task skipped after claim check: taskId={}", message.taskId());
            return;
        }
        log.info("import task processed: taskId={}, success={}, message={}",
                result.taskId(), result.success(), result.message());
    }

    public String[] topics() {
        WorkerRegistration registration = workerLoop.ensureStarted();
        if (registration == null || registration.getWorkerId() == null || registration.getWorkerId().isBlank()) {
            return new String[] {configuration.topic()};
        }
        return new String[] {
                configuration.topic(),
                BatchTopics.directDispatchTopic(configuration.topic(), registration.getWorkerId())
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
