package com.example.batch.worker.core.support;

import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.worker.core.application.TaskDispatchExecutor;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.domain.WorkerExecutionResult;
import com.example.batch.worker.core.domain.WorkerRegistration;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared Kafka consumer template for all worker types.
 *
 * <p>Subclasses provide the three collaborators ({@link #workerLoop()},
 * {@link #workerConfiguration()}, {@link #taskDispatchExecutor()}) and a thin
 * {@code @KafkaListener} method that calls {@link #doConsume(String)}.
 * All message routing, filtering, and execution-result logging logic lives here.
 */
@Slf4j
public abstract class AbstractTaskConsumer {

    /** The worker loop associated with this consumer (used for {@code ensureStarted}). */
    protected abstract AbstractWorkerLoop workerLoop();

    /** Worker-specific configuration. */
    protected abstract WorkerConfiguration workerConfiguration();

    /** Executor that claims and runs the dispatched task. */
    protected abstract TaskDispatchExecutor taskDispatchExecutor();

    /**
     * Called by each subclass's {@code @KafkaListener} method.
     * Keeping {@code @KafkaListener} in the subclass avoids per-worker annotation duplication
     * at the abstract level.
     */
    protected void doConsume(String payload) {
        TaskDispatchMessage message = JsonUtils.fromJson(payload, TaskDispatchMessage.class);
        WorkerRegistration registration = workerLoop().ensureStarted();
        if (!accepts(message, registration)) {
            return;
        }
        WorkerExecutionResult result = taskDispatchExecutor().execute(message, registration.getWorkerId());
        if (result == null) {
            log.info("{} task skipped after claim check: taskId={}",
                    workerConfiguration().workerType(), message.taskId());
            return;
        }
        log.info("{} task processed: taskId={}, success={}, message={}",
                workerConfiguration().workerType(), result.taskId(), result.success(), result.message());
    }

    /**
     * Returns the Kafka topics this consumer listens on.
     * Exposed as a public method so that subclass {@code @KafkaListener} SpEL expressions can
     * reference it via {@code #{__listener.topics()}}.
     */
    public String[] topics() {
        WorkerConfiguration cfg = workerConfiguration();
        String configuredWorkerCode = cfg.workerCode();
        if (configuredWorkerCode == null || configuredWorkerCode.isBlank()) {
            return new String[]{cfg.topic()};
        }
        return new String[]{
                cfg.topic(),
                BatchTopics.directDispatchTopic(cfg.topic(), configuredWorkerCode)
        };
    }

    /**
     * Returns the Kafka consumer group id.
     * Exposed as a public method so that subclass {@code @KafkaListener} SpEL expressions can
     * reference it via {@code #{__listener.consumerGroupId()}}.
     */
    public String consumerGroupId() {
        return workerConfiguration().consumerGroupId();
    }

    private boolean accepts(TaskDispatchMessage message, WorkerRegistration registration) {
        WorkerConfiguration cfg = workerConfiguration();
        return message != null
                && cfg.workerType() != null
                && cfg.workerType().equalsIgnoreCase(message.workerType())
                && (message.selectedWorkerId() == null
                || (registration != null && message.selectedWorkerId().equals(registration.getWorkerId())));
    }
}
