package com.example.batch.worker.core.support;

import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.common.logging.BatchMdc;
import com.example.batch.common.logging.StructuredLogField;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.worker.core.application.TaskDispatchExecutor;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.domain.WorkerExecutionResult;
import com.example.batch.worker.core.domain.WorkerRegistration;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Kafka 消费骨架（所有 worker 通用）。
 *
 * <p>设计目标：把“通用的消费/路由/幂等/日志字段注入”集中在一处，避免 import/export/dispatch 三条链路各自演化出不一致实现。
 *
 * <p>子类只需要：
 * <ul>
 *   <li>提供 {@link #workerLoop()} / {@link #workerConfiguration()} / {@link #taskDispatchExecutor()}</li>
 *   <li>写一个很薄的 {@code @KafkaListener} 方法，把原始 payload 交给 {@link #doConsume(String)}</li>
 * </ul>
 *
 * <p>该类负责：
 * <ul>
 *   <li>反序列化 {@link TaskDispatchMessage}</li>
 *   <li>启动保障（确保 worker 已注册）</li>
 *   <li>MDC 注入（tenantId/traceId/taskId/workerId 等）以保证日志可关联</li>
 * </ul>
 */
@Slf4j
public abstract class AbstractTaskConsumer {

    /** 关联的 worker loop（用于 ensureStarted，保证注册完成后再执行 claim/处理）。 */
    protected abstract AbstractWorkerLoop workerLoop();

    /** Worker 侧配置（topic、workerType 等）。 */
    protected abstract WorkerConfiguration workerConfiguration();

    /** 实际执行器：负责 claim + 执行业务 pipeline + report。 */
    protected abstract TaskDispatchExecutor taskDispatchExecutor();

    /**
     * KafkaListener 的 container id。
     *
     * <p>背压实现需要在运行时对 container 做 pause/resume，因此这里要求子类固定配置 listenerId，
     * 并在 {@code @KafkaListener(id = "...")} 中保持一致。
     */
    protected abstract String listenerId();

    @Value("${batch.worker.max-concurrent-tasks:8}")
    private int maxConcurrentTasks;

    private final KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;
    private volatile Semaphore semaphore;

    protected AbstractTaskConsumer(KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry) {
        this.kafkaListenerEndpointRegistry = kafkaListenerEndpointRegistry;
    }

    /**
     * 由子类的 {@code @KafkaListener} 方法调用。
     * <p>把监听注解留在子类，避免抽象类强耦合 listener 配置与 topic 表达式。
     */
    protected boolean doConsume(String payload) {
        Semaphore sem = ensureSemaphore();
        boolean acquired = sem.tryAcquire();
        if (!acquired) {
            // 背压：当实例内并发达到上限时，暂停拉取新消息。
            // 注意：这里不阻塞线程等待 permit（避免 Kafka consumer 线程卡死导致 rebalance 风险）。
            pauseContainer();
            return false;
        }
        TaskDispatchMessage message = JsonUtils.fromJson(payload, TaskDispatchMessage.class);
        WorkerRegistration registration = workerLoop().ensureStarted();
        if (!accepts(message, registration)) {
            sem.release();
            return true;
        }
        injectMdc(message, registration);
        try {
            WorkerExecutionResult result = taskDispatchExecutor().execute(message, registration.getWorkerId());
            if (result == null) {
                log.info("{} task skipped after claim check: taskId={}",
                        workerConfiguration().workerType(), message.taskId());
                return true;
            }
            log.info("{} task processed: taskId={}, success={}, message={}",
                    workerConfiguration().workerType(), result.taskId(), result.success(), result.message());
            return true;
        } finally {
            // 无论处理成功/失败/抛异常，都必须释放 permit；
            // 若之前触发过 pause，则在释放后尝试恢复消费。
            sem.release();
            resumeContainerIfPaused();
            BatchMdc.remove(StructuredLogField.TENANT_ID);
            BatchMdc.remove(StructuredLogField.TRACE_ID);
            BatchMdc.remove(StructuredLogField.TASK_ID);
            BatchMdc.remove(StructuredLogField.JOB_INSTANCE_ID);
            BatchMdc.remove(StructuredLogField.WORKER_TYPE);
            BatchMdc.remove(StructuredLogField.WORKER_ID);
        }
    }

    private Semaphore ensureSemaphore() {
        Semaphore local = semaphore;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (semaphore == null) {
                int permits = Math.max(1, maxConcurrentTasks);
                semaphore = new Semaphore(permits);
            }
            return semaphore;
        }
    }

    private void pauseContainer() {
        MessageListenerContainer container = kafkaListenerEndpointRegistry.getListenerContainer(listenerId());
        if (container == null) {
            return;
        }
        try {
            if (!container.isPauseRequested()) {
                container.pause();
            }
        } catch (Exception ex) {
            log.warn("failed to pause container: listenerId={}, error={}", listenerId(), ex.getMessage(), ex);
        }
    }

    private void resumeContainerIfPaused() {
        MessageListenerContainer container = kafkaListenerEndpointRegistry.getListenerContainer(listenerId());
        if (container == null) {
            return;
        }
        try {
            if (container.isPauseRequested()) {
                container.resume();
            }
        } catch (Exception ex) {
            log.warn("failed to resume container: listenerId={}, error={}", listenerId(), ex.getMessage(), ex);
        }
    }

    private void injectMdc(TaskDispatchMessage message, WorkerRegistration registration) {
        BatchMdc.put(StructuredLogField.TENANT_ID, message.tenantId());
        BatchMdc.put(StructuredLogField.TRACE_ID, message.traceId());
        BatchMdc.put(StructuredLogField.TASK_ID, message.taskId() == null ? null : String.valueOf(message.taskId()));
        BatchMdc.put(StructuredLogField.JOB_INSTANCE_ID, message.jobInstanceId() == null ? null : String.valueOf(message.jobInstanceId()));
        BatchMdc.put(StructuredLogField.WORKER_TYPE, workerConfiguration().workerType());
        BatchMdc.put(StructuredLogField.WORKER_ID, registration == null ? null : registration.getWorkerId());
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
