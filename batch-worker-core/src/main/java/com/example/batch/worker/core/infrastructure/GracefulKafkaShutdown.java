package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.support.WorkerSelfRegistrationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

/**
 * Spring {@link ContextClosedEvent} 监听器，负责 Worker 进程的有序关机。
 *
 * <p><b>三步关机顺序</b>（顺序不可交换）：
 *
 * <ol>
 *   <li>将所有已注册 worker 状态更新为 {@code DRAINING}，通知 Orchestrator 在调度层面停止派发新任务。
 *   <li>调用 {@link KafkaListenerEndpointRegistry#stop()} 停止所有 Kafka 消费者容器， 确保不再拉取新消息（即使 DRAINING
 *       信号尚未被 Orchestrator 处理）。
 *   <li>调用 {@link ActiveTaskLeaseRegistry#awaitDrain} 等待 in-flight 任务自然结束， 超时由 {@code
 *       batch.worker.graceful-shutdown.timeout-seconds}（默认 120s）控制。
 * </ol>
 *
 * <p><b>v6 hardening · drain 可观测性</b>：记录 drain 持续时间、起始 active lease 数量、是否触发 timeout。 让运维可以在
 * Prometheus 上观察 drain 健康度，提前发现 timeout 不够 / Kafka 缓冲过深导致的 stuck shutdown：
 *
 * <ul>
 *   <li>{@code batch.worker.drain.duration_seconds}（timer）：每次 drain 整体耗时
 *   <li>{@code batch.worker.drain.initial_active_leases}（counter）：drain 开始时的 active lease 总数
 *   <li>{@code batch.worker.drain.outcome_total}（counter，tag {@code
 *       outcome=success|timeout}）：清晰区分干净排空 vs 强制超时
 * </ul>
 */
@Slf4j
@Component
public class GracefulKafkaShutdown implements ApplicationListener<ContextClosedEvent> {

  private static final String METRIC_DURATION = "batch.worker.drain.duration_seconds";
  private static final String METRIC_INITIAL_LEASES = "batch.worker.drain.initial_active_leases";
  private static final String METRIC_OUTCOME = "batch.worker.drain.outcome_total";

  private final WorkerRuntimeState workerRuntimeState;
  private final WorkerSelfRegistrationService workerRegistryService;
  private final KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;
  private final ActiveTaskLeaseRegistry activeTaskLeaseRegistry;
  private final ObjectProvider<MeterRegistry> meterRegistryProvider;

  @Value("${batch.worker.graceful-shutdown.timeout-seconds:120}")
  private long gracefulShutdownTimeoutSeconds;

  public GracefulKafkaShutdown(
      WorkerRuntimeState workerRuntimeState,
      WorkerSelfRegistrationService workerRegistryService,
      KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry,
      ActiveTaskLeaseRegistry activeTaskLeaseRegistry,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this.workerRuntimeState = workerRuntimeState;
    this.workerRegistryService = workerRegistryService;
    this.kafkaListenerEndpointRegistry = kafkaListenerEndpointRegistry;
    this.activeTaskLeaseRegistry = activeTaskLeaseRegistry;
    this.meterRegistryProvider = meterRegistryProvider;
  }

  @Override
  public void onApplicationEvent(ContextClosedEvent event) {
    // 关闭顺序的意图：
    // 1) 先把 worker 标记为 DRAINING（best-effort），让 orchestrator 在调度层面减少派发
    // 2) 再停止 Kafka listener，避免继续拉取新任务
    // 3) 最后等待 in-flight 任务自然结束（有超时），避免 RUNNING 卡死/重复调度
    Collection<WorkerRegistration> registrations = workerRuntimeState.snapshot();
    for (WorkerRegistration registration : registrations) {
      if (!isValidRegistration(registration)) {
        continue;
      }
      try {
        workerRegistryService.updateStatus(registration, WorkerRegistryStatus.DRAINING.code());
      } catch (Exception ex) {
        log.warn(
            "failed to mark worker draining on shutdown: workerId={}, cause={}",
            registration.getWorkerId(),
            ex.getMessage());
      }
    }

    try {
      kafkaListenerEndpointRegistry.stop();
      log.info("kafka listener containers stopped");
    } catch (Exception ex) {
      log.warn("failed to stop kafka listener containers: {}", ex.getMessage(), ex);
    }

    awaitDrainAndRecord();
  }

  private void awaitDrainAndRecord() {
    int initialActive = activeTaskLeaseRegistry.size();
    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    if (registry != null && initialActive > 0) {
      Counter.builder(METRIC_INITIAL_LEASES).register(registry).increment(initialActive);
    }
    long startNanos = System.nanoTime();
    boolean drained = false;
    try {
      drained =
          activeTaskLeaseRegistry.awaitDrain(
              Duration.ofSeconds(Math.max(0L, gracefulShutdownTimeoutSeconds)));
    } catch (Exception ex) {
      log.warn("failed to await in-flight tasks drain: {}", ex.getMessage(), ex);
    }
    long elapsedNanos = System.nanoTime() - startNanos;
    if (registry != null) {
      Timer.builder(METRIC_DURATION).register(registry).record(elapsedNanos, TimeUnit.NANOSECONDS);
      Counter.builder(METRIC_OUTCOME)
          .tags(Tags.of("outcome", drained ? "success" : "timeout"))
          .register(registry)
          .increment();
    }
    log.info(
        "worker drain completed: outcome={}, initialActive={}, remainingLeases={},"
            + " elapsedMs={}, timeoutSeconds={}",
        drained ? "success" : "timeout",
        initialActive,
        activeTaskLeaseRegistry.size(),
        TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
        gracefulShutdownTimeoutSeconds);
  }

  private boolean isValidRegistration(WorkerRegistration registration) {
    return registration != null
        && registration.getWorkerId() != null
        && !registration.getWorkerId().isBlank();
  }
}
