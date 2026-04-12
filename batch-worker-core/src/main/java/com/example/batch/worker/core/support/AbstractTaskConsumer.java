package com.example.batch.worker.core.support;

import com.example.batch.common.context.RunModeSupport;
import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.common.logging.BatchMdc;
import com.example.batch.common.logging.StructuredLogField;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.worker.core.application.TaskDispatchExecutor;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.domain.WorkerExecutionResult;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.infrastructure.DeadLetterPublisher;
import java.util.List;
import java.util.Map;
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
 *
 * <ul>
 *   <li>提供 {@link #workerLoop()} / {@link #workerConfiguration()} / {@link #taskDispatchExecutor()}
 *   <li>写一个很薄的 {@code @KafkaListener} 方法，把原始 payload 交给 {@link #doConsume(String)}
 * </ul>
 *
 * <p>该类负责：
 *
 * <ul>
 *   <li>反序列化 {@link TaskDispatchMessage}
 *   <li>启动保障（确保 worker 已注册）
 *   <li>MDC 注入（tenantId/traceId/taskId/workerId 等）以保证日志可关联
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
   * <p>背压实现需要在运行时对 container 做 pause/resume，因此这里要求子类固定配置 listenerId， 并在 {@code @KafkaListener(id =
   * "...")} 中保持一致。
   */
  protected abstract String listenerId();

  /** D-3: 子类可提供 DLQ 发布器，用于转发无法处理的"毒丸"消息。 */
  protected abstract DeadLetterPublisher deadLetterPublisher();

  @Value("${batch.worker.max-concurrent-tasks:8}")
  private int maxConcurrentTasks;

  private final KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;
  private volatile Semaphore semaphore;

  protected AbstractTaskConsumer(KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry) {
    this.kafkaListenerEndpointRegistry = kafkaListenerEndpointRegistry;
  }

  /**
   * 由子类的 {@code @KafkaListener} 方法调用。
   *
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
      WorkerExecutionResult result =
          taskDispatchExecutor().execute(message, registration.getWorkerId());
      if (result == null) {
        log.info(
            "{} task skipped after claim check: taskId={}",
            workerConfiguration().workerType(),
            message.taskId());
        return true;
      }
      log.info(
          "{} task processed: taskId={}, success={}, message={}",
          workerConfiguration().workerType(),
          result.taskId(),
          result.success(),
          result.message());
      return true;
    } catch (Exception ex) {
      // D-3: 发送至 DLQ 并确认偏移量，防止毒丸消息阻塞队列；运维可从 DLQ 查看并重放
      log.error(
          "{} task execution failed — publishing to DLQ: taskId={}, error={}",
          workerConfiguration().workerType(),
          message.taskId(),
          ex.getMessage(),
          ex);
      DeadLetterPublisher dlq = deadLetterPublisher();
      if (dlq != null) {
        dlq.publish(
            payload,
            workerConfiguration().topic(),
            workerConfiguration().workerType(),
            ex.getMessage());
      }
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
      BatchMdc.remove(StructuredLogField.RUN_MODE);
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
    MessageListenerContainer container =
        kafkaListenerEndpointRegistry.getListenerContainer(listenerId());
    if (container == null) {
      return;
    }
    try {
      if (!container.isPauseRequested()) {
        container.pause();
      }
    } catch (Exception ex) {
      log.warn(
          "failed to pause container: listenerId={}, error={}", listenerId(), ex.getMessage(), ex);
    }
  }

  private void resumeContainerIfPaused() {
    MessageListenerContainer container =
        kafkaListenerEndpointRegistry.getListenerContainer(listenerId());
    if (container == null) {
      return;
    }
    try {
      if (container.isPauseRequested()) {
        container.resume();
      }
    } catch (Exception ex) {
      log.warn(
          "failed to resume container: listenerId={}, error={}", listenerId(), ex.getMessage(), ex);
    }
  }

  private void injectMdc(TaskDispatchMessage message, WorkerRegistration registration) {
    BatchMdc.put(StructuredLogField.TENANT_ID, message.tenantId());
    BatchMdc.put(StructuredLogField.TRACE_ID, message.traceId());
    BatchMdc.put(
        StructuredLogField.TASK_ID,
        message.taskId() == null ? null : String.valueOf(message.taskId()));
    BatchMdc.put(
        StructuredLogField.JOB_INSTANCE_ID,
        message.jobInstanceId() == null ? null : String.valueOf(message.jobInstanceId()));
    BatchMdc.put(StructuredLogField.WORKER_TYPE, workerConfiguration().workerType());
    BatchMdc.put(
        StructuredLogField.WORKER_ID, registration == null ? null : registration.getWorkerId());
    BatchMdc.put(StructuredLogField.RUN_MODE, resolveRunMode(message));
  }

  @SuppressWarnings("unchecked")
  private String resolveRunMode(TaskDispatchMessage message) {
    if (message == null || message.payload() == null || message.payload().isBlank()) {
      return null;
    }
    try {
      Object payloadObject = JsonUtils.fromJson(message.payload(), Object.class);
      if (payloadObject instanceof Map<?, ?> payloadMap) {
        return RunModeSupport.resolveCode((Map<String, Object>) payloadMap);
      }
    } catch (RuntimeException ignored) {
      // payload 格式异常时忽略，保持日志正常输出（不含 run_mode）。
    }
    return null;
  }

  /**
   * 返回该消费者监听的 Kafka topic 列表；公开为 public 方法，供子类 {@code @KafkaListener} SpEL 表达式 通过 {@code
   * #{__listener.topics()}} 引用。
   */
  public String[] topics() {
    WorkerConfiguration cfg = workerConfiguration();
    String configuredWorkerCode = cfg.workerCode();
    String baseTopic = cfg.topic();
    if (baseTopic == null || baseTopic.isBlank()) {
      // e2e / local 环境可能不完整注入 topic；兜底保证 @KafkaListener(topics=...) 不会解析成 null。
      baseTopic = resolveTopicByWorkerType(cfg.workerType());
      // 最后兜底：仅根据 workerCode 文本推断，避免返回空数组导致 Spring Kafka 启动失败。
      if (baseTopic == null || baseTopic.isBlank()) {
        baseTopic = resolveTopicByWorkerCode(configuredWorkerCode);
      }
    }
    if (baseTopic == null || baseTopic.isBlank()) {
      // 保底：绝不返回空数组，确保 @KafkaListener(topics=...) 至少有一个值可用。
      baseTopic = BatchTopics.TASK_DISPATCH_DISPATCH;
    }
    if (configuredWorkerCode == null || configuredWorkerCode.isBlank()) {
      return new String[] {baseTopic};
    }
    return new String[] {
      baseTopic, BatchTopics.directDispatchTopic(baseTopic, configuredWorkerCode)
    };
  }

  /**
   * 返回 Kafka 消费者组 ID；公开为 public 方法，供子类 {@code @KafkaListener} SpEL 表达式 通过 {@code
   * #{__listener.consumerGroupId()}} 引用。
   */
  public String consumerGroupId() {
    return workerConfiguration().consumerGroupId();
  }

  private static final Map<String, String> WORKER_TYPE_TOPIC =
      Map.of(
          "IMPORT", BatchTopics.TASK_DISPATCH_IMPORT,
          "EXPORT", BatchTopics.TASK_DISPATCH_EXPORT,
          "DISPATCH", BatchTopics.TASK_DISPATCH_DISPATCH);

  // workerCode 推断：按 contains 关键词顺序匹配，顺序有意义（import → export → dispatch）
  private static final List<Map.Entry<String, String>> WORKER_CODE_KEYWORD_TOPIC =
      List.of(
          Map.entry("import", BatchTopics.TASK_DISPATCH_IMPORT),
          Map.entry("export", BatchTopics.TASK_DISPATCH_EXPORT),
          Map.entry("dispatch", BatchTopics.TASK_DISPATCH_DISPATCH));

  private String resolveTopicByWorkerType(String workerType) {
    if (workerType == null || workerType.isBlank()) {
      return null;
    }
    return WORKER_TYPE_TOPIC.get(workerType.toUpperCase());
  }

  private String resolveTopicByWorkerCode(String workerCode) {
    String wc = workerCode == null ? "" : workerCode.toLowerCase();
    return WORKER_CODE_KEYWORD_TOPIC.stream()
        .filter(entry -> wc.contains(entry.getKey()))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  private boolean accepts(TaskDispatchMessage message, WorkerRegistration registration) {
    if (message == null) {
      return false;
    }
    // M-12: 分发前校验必填字段，防止下游 NPE
    if (message.taskId() == null || message.tenantId() == null || message.workerType() == null) {
      log.warn(
          "malformed dispatch message dropped — missing required fields: taskId={}, tenantId={},"
              + " workerType={}",
          message.taskId(),
          message.tenantId(),
          message.workerType());
      return false;
    }
    WorkerConfiguration cfg = workerConfiguration();
    return cfg.workerType() != null
        && cfg.workerType().equalsIgnoreCase(message.workerType())
        && (message.selectedWorkerId() == null
            || (registration != null
                && message.selectedWorkerId().equals(registration.getWorkerId())));
  }
}
