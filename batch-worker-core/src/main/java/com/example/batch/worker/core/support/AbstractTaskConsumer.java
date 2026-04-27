package com.example.batch.worker.core.support;

import com.example.batch.common.kafka.BatchTopics;
import com.example.batch.common.kafka.TaskDispatchMessage;
import com.example.batch.common.logging.BatchMdc;
import com.example.batch.common.logging.StructuredLogField;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.worker.core.application.TaskDispatchExecutor;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.config.WorkerKafkaSubscribeProperties;
import com.example.batch.worker.core.domain.WorkerExecutionResult;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.infrastructure.DeadLetterPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
  // #6-2: 注入 MeterRegistry 用于暴露信号量可用许可数
  private final ObjectProvider<MeterRegistry> meterRegistryProvider;

  // P2-5 worker 端 Kafka 订阅模式开关；required=false 让旧测试 / 不开启此特性的 e2e 也能起，
  // 注入不到时 topicPattern() 走默认 PATTERN 行为。
  @Autowired(required = false)
  private WorkerKafkaSubscribeProperties subscribeProperties;

  private volatile Semaphore semaphore;

  protected AbstractTaskConsumer(
      KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this.kafkaListenerEndpointRegistry = kafkaListenerEndpointRegistry;
    this.meterRegistryProvider = meterRegistryProvider;
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
    // C-2.2: 所有 acquired=true 之后的出口（包括 JSON 解析异常 / accepts 失败 / 业务异常）
    // 都必须经过 finally 释放 permit。之前 try 从 executor 调用才起，
    // 导致 JsonUtils.fromJson 抛异常时 permit 泄漏，多次触发后信号量耗尽 → 背压失效。
    TaskDispatchMessage message = null;
    try {
      message = JsonUtils.fromJson(payload, TaskDispatchMessage.class);
      WorkerRegistration registration = workerLoop().ensureStarted();
      if (!accepts(message, registration)) {
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
        // #4-3: 先确认 DLQ 写入成功再提交偏移量，避免消息双重丢失
        if (!publishToDlqSafely(payload, ex.getMessage())) {
          // DLQ 写入失败：不提交偏移量，Kafka 将重新投递该消息
          log.error(
              "{} DLQ 写入失败，不提交偏移量以便消息重新投递: taskId={}",
              workerConfiguration().workerType(),
              message.taskId());
          return false;
        }
        return true;
      }
    } catch (Exception parseOrStartupEx) {
      // 解析 / ensureStarted 阶段的异常：payload 无法反序列化或 worker 未起来。
      // 送 DLQ 避免毒丸阻塞队列；若 DLQ 也挂了则 return false 让 Kafka 重投。
      log.error(
          "{} pre-dispatch failed — publishing to DLQ: error={}",
          workerConfiguration().workerType(),
          parseOrStartupEx.getMessage(),
          parseOrStartupEx);
      if (!publishToDlqSafely(payload, parseOrStartupEx.getMessage())) {
        return false;
      }
      return true;
    } finally {
      // 无论处理成功/失败/抛异常，都必须释放 permit；
      // 若之前触发过 pause，则在释放后尝试恢复消费。
      sem.release();
      resumeContainerIfPaused();
      BatchMdc.removeAll(
          StructuredLogField.TENANT_ID,
          StructuredLogField.TRACE_ID,
          StructuredLogField.TASK_ID,
          StructuredLogField.JOB_INSTANCE_ID,
          StructuredLogField.WORKER_TYPE,
          StructuredLogField.WORKER_ID,
          StructuredLogField.RUN_MODE);
    }
  }

  /** #4-3: 安全发布到 DLQ，返回是否成功。DLQ 写入失败时返回 false，调用方据此决定是否提交偏移量。 */
  private boolean publishToDlqSafely(String payload, String errorMessage) {
    DeadLetterPublisher dlq = deadLetterPublisher();
    if (dlq == null) {
      return true; // 无 DLQ 配置，视为成功（避免无限重投递）
    }
    try {
      dlq.publish(
          payload, workerConfiguration().topic(), workerConfiguration().workerType(), errorMessage);
      return true;
    } catch (Exception dlqEx) {
      log.error(
          "{} DLQ 发布失败: dlqError={}",
          workerConfiguration().workerType(),
          dlqEx.getMessage(),
          dlqEx);
      return false;
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
        // #6-2: 暴露信号量可用许可数到 Actuator/Prometheus
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
          Semaphore captured = semaphore;
          registry.gauge(
              "batch.worker.semaphore.available",
              Tags.of("workerType", workerConfiguration().workerType()),
              captured,
              Semaphore::availablePermits);
        }
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
    // P1-2.2:RUN_MODE 不再注入到 pre-claim MDC(payload 已从 message 移除);
    // worker 业务 pipeline 通过 ExecutionContext.attributes 拿 run_mode(由
    // DefaultTaskExecutionWrapper 在 claim 后注入),业务日志依然能看到。
  }

  /**
   * 返回该消费者监听的 Kafka topic 列表；公开为 public 方法，供子类 {@code @KafkaListener} SpEL 表达式 通过 {@code
   * #{__listener.topics()}} 引用。
   *
   * <p>P2-5 之后已废弃 — 推荐用 {@link #topicPattern()} 代替；保留本方法兼容老子类（{@code @KafkaListener(topics=...)}） 仅
   * SINGLE 模式下能匹配 producer 端实际写出的 topic。
   */
  public String[] topics() {
    WorkerConfiguration cfg = workerConfiguration();
    String configuredWorkerCode = cfg.workerCode();
    String baseTopic = resolveBaseTopic(cfg);
    if (configuredWorkerCode == null || configuredWorkerCode.isBlank()) {
      return new String[] {baseTopic};
    }
    return new String[] {
      baseTopic, BatchTopics.directDispatchTopic(baseTopic, configuredWorkerCode)
    };
  }

  /**
   * P2-5 worker 端 Kafka pattern 适配：用宽松 regex 同时匹配 SINGLE / TENANT / PRIORITY 三种 producer 输出的 topic
   * 形态。Kafka client 按 {@code metadata.max.age.ms} 周期重新发现匹配 topic，新增 tenant / priority 后缀 topic 会自动被
   * worker 拾取。
   *
   * <p>匹配规则（base = 例如 {@code batch.task.dispatch.import}）：
   *
   * <ul>
   *   <li>{@code base}（exact）：SINGLE 模式 producer 写出的固定 topic
   *   <li>{@code base.node.{ourWorkerCode}}（exact）：直达分发 topic（粘性路由）
   *   <li>{@code base.<single-segment>}（一段后缀，不含 dot）：TENANT 后缀（{@code .default-tenant}） / PRIORITY
   *       后缀（{@code .high}）
   * </ul>
   *
   * <p><b>不匹配</b> {@code base.node.{otherWorkerCode}} —— 直达 topic 是双段后缀，由 {@code .[^.]+} 排除。
   *
   * @return Kafka topic 正则 pattern（已转义 base 中的点；调用方直接传给 {@code @KafkaListener.topicPattern}）
   */
  public String topicPattern() {
    WorkerConfiguration cfg = workerConfiguration();
    String baseTopic = resolveBaseTopic(cfg);
    String safeBase = baseTopic.replace(".", "\\.");
    String configuredWorkerCode = cfg.workerCode();
    String nodeDirect =
        (configuredWorkerCode == null || configuredWorkerCode.isBlank())
            ? null
            : "\\.node\\." + escapeRegex(configuredWorkerCode);

    WorkerKafkaSubscribeProperties.Mode mode =
        subscribeProperties == null
            ? WorkerKafkaSubscribeProperties.Mode.PATTERN
            : subscribeProperties.getSubscribeMode();

    String suffixAlt;
    switch (mode) {
      case FIXED:
        // 仅 base + 自己的 node-direct，不订阅任何后缀；producer 处于 SINGLE 模式时等价
        suffixAlt = nodeDirect;
        break;
      case TENANT_SCOPED:
        // 只订阅 allowlist 中的 tenant 后缀（+ node-direct）；其他 tenant 的后缀不接
        List<String> allow =
            subscribeProperties.getTenantAllowlist() == null
                ? List.of()
                : subscribeProperties.getTenantAllowlist();
        if (allow.isEmpty()) {
          suffixAlt = nodeDirect;
        } else {
          String tenantAlt =
              allow.stream()
                  .filter(s -> s != null && !s.isBlank())
                  .map(AbstractTaskConsumer::escapeRegex)
                  .reduce((a, b) -> a + "|" + b)
                  .orElse(null);
          String tenantBranch = tenantAlt == null ? null : "\\.(" + tenantAlt + ")";
          suffixAlt = joinAlt(nodeDirect, tenantBranch);
        }
        break;
      case PATTERN:
      default:
        // 宽松：base / base.<single-segment> / base.node.<workerCode>
        suffixAlt = joinAlt(nodeDirect, "\\.[^.]+");
    }
    if (suffixAlt == null) {
      return "^" + safeBase + "$";
    }
    return "^" + safeBase + "(" + suffixAlt + ")?$";
  }

  private static String joinAlt(String a, String b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a + "|" + b;
  }

  private static String escapeRegex(String value) {
    return value.replaceAll("([\\\\\\.\\[\\]\\(\\)\\{\\}\\^\\$\\|\\?\\*\\+])", "\\\\$1");
  }

  private String resolveBaseTopic(WorkerConfiguration cfg) {
    String configuredWorkerCode = cfg.workerCode();
    String baseTopic = cfg.topic();
    if (baseTopic == null || baseTopic.isBlank()) {
      baseTopic = resolveTopicByWorkerType(cfg.workerType());
      if (baseTopic == null || baseTopic.isBlank()) {
        baseTopic = resolveTopicByWorkerCode(configuredWorkerCode);
      }
    }
    if (baseTopic == null || baseTopic.isBlank()) {
      baseTopic = BatchTopics.TASK_DISPATCH_DISPATCH;
    }
    return baseTopic;
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
