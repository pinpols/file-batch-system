package io.github.pinpols.batch.worker.core.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * 负责 worker 消费容量和 Kafka 拉取节奏之间的协调。
 *
 * <p>当本地执行许可耗尽时暂停对应 listener，避免消费线程阻塞在等待锁上并触发 Kafka rebalance；执行完成
 * 后再恢复拉取。信号量和指标属于运行时资源治理，不应和 claim、业务执行或 offset 决策混在消费骨架中。
 */
@Slf4j
final class TaskConsumerBackpressureController {

  private final KafkaListenerEndpointRegistry listenerRegistry;
  private final ObjectProvider<MeterRegistry> meterRegistryProvider;
  private final int maxConcurrentTasks;
  private final Supplier<String> workerTypeSupplier;
  private final AtomicReference<Semaphore> semaphore = new AtomicReference<>();
  private final AtomicReference<Counter> pauseCounter = new AtomicReference<>();
  private final AtomicReference<Counter> resumeCounter = new AtomicReference<>();

  TaskConsumerBackpressureController(
      KafkaListenerEndpointRegistry listenerRegistry,
      ObjectProvider<MeterRegistry> meterRegistryProvider,
      int maxConcurrentTasks,
      Supplier<String> workerTypeSupplier) {
    this.listenerRegistry = listenerRegistry;
    this.meterRegistryProvider = meterRegistryProvider;
    this.maxConcurrentTasks = maxConcurrentTasks;
    this.workerTypeSupplier = workerTypeSupplier;
  }

  void initialize() {
    ensureSemaphore();
  }

  Semaphore semaphore() {
    return ensureSemaphore();
  }

  int currentLoad() {
    Semaphore current = semaphore.get();
    if (current == null) {
      return 0;
    }
    int inFlight = maxConcurrentTasks - current.availablePermits();
    return Math.max(0, inFlight);
  }

  void pause(String containerId) {
    MessageListenerContainer container = listenerRegistry.getListenerContainer(containerId);
    if (container == null) {
      return;
    }
    try {
      if (!container.isPauseRequested()) {
        container.pause();
        Counter counter = pauseCounter.get();
        if (counter != null) {
          counter.increment();
        }
      }
    } catch (Exception ex) {
      log.warn(
          "failed to pause container: listenerId={}, error={}", containerId, ex.getMessage(), ex);
    }
  }

  void resumeIfPaused(String containerId) {
    MessageListenerContainer container = listenerRegistry.getListenerContainer(containerId);
    if (container == null) {
      return;
    }
    try {
      if (container.isPauseRequested()) {
        container.resume();
        Counter counter = resumeCounter.get();
        if (counter != null) {
          counter.increment();
        }
      }
    } catch (Exception ex) {
      log.warn(
          "failed to resume container: listenerId={}, error={}", containerId, ex.getMessage(), ex);
    }
  }

  private Semaphore ensureSemaphore() {
    Semaphore current = semaphore.get();
    if (current != null) {
      return current;
    }
    synchronized (this) {
      current = semaphore.get();
      if (current == null) {
        int permits = Math.max(1, maxConcurrentTasks);
        current = new Semaphore(permits);
        semaphore.set(current);
        registerMetrics(current);
      }
      return current;
    }
  }

  private void registerMetrics(Semaphore current) {
    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    if (registry == null) {
      return;
    }
    String workerType = workerTypeSupplier.get();
    registry.gauge(
        "batch.worker.semaphore.available",
        Tags.of("workerType", workerType),
        current,
        Semaphore::availablePermits);
    pauseCounter.set(Counter.builder("batch.worker.consumer.pause.total")
        .description("Kafka listener pause events caused by exhausted worker permits")
        .tag("workerType", workerType)
        .register(registry));
    resumeCounter.set(Counter.builder("batch.worker.consumer.resume.total")
        .description("Kafka listener resume events after worker permits became available")
        .tag("workerType", workerType)
        .register(registry));
  }
}
