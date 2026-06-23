package io.github.pinpols.batch.worker.imports.runtime;

import io.github.pinpols.batch.common.kafka.TaskDispatchMessage;
import io.github.pinpols.batch.worker.core.application.TaskDispatchExecutor;
import io.github.pinpols.batch.worker.core.config.WorkerConfiguration;
import io.github.pinpols.batch.worker.core.infrastructure.DeadLetterPublisher;
import io.github.pinpols.batch.worker.core.support.AbstractTaskConsumer;
import io.github.pinpols.batch.worker.core.support.AbstractWorkerLoop;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Service;

/**
 * Import Worker 的 Kafka 消费者：监听 {@code topics()} 主题，将消息委托给 {@link AbstractTaskConsumer#doConsume}
 * 处理（CLAIM → EXECUTE → REPORT 链路）。
 *
 * <p>消费成功（{@code doConsume} 返回 true）后手动提交 offset； {@code listenerId} 为 {@code
 * "import-task-consumer"}，与 ShedLock / 指标标签一致。
 */
@Service
public class ImportTaskConsumer extends AbstractTaskConsumer {

  private final ImportWorkerLoop workerLoop;
  private final ImportWorkerConfiguration configuration;
  private final TaskDispatchExecutor taskDispatchExecutor;
  private final DeadLetterPublisher deadLetterPublisher;

  public ImportTaskConsumer(
      ImportWorkerLoop workerLoop,
      ImportWorkerConfiguration configuration,
      TaskDispatchExecutor taskDispatchExecutor,
      KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry,
      DeadLetterPublisher deadLetterPublisher,
      ObjectProvider<MeterRegistry> meterRegistryProvider,
      @Value("${batch.worker.max-concurrent-tasks:8}") int maxConcurrentTasks) {
    super(kafkaListenerEndpointRegistry, meterRegistryProvider, maxConcurrentTasks);
    this.workerLoop = workerLoop;
    this.configuration = configuration;
    this.taskDispatchExecutor = taskDispatchExecutor;
    this.deadLetterPublisher = deadLetterPublisher;
  }

  @Override
  protected AbstractWorkerLoop workerLoop() {
    return workerLoop;
  }

  @Override
  protected WorkerConfiguration workerConfiguration() {
    return configuration;
  }

  @Override
  protected TaskDispatchExecutor taskDispatchExecutor() {
    return taskDispatchExecutor;
  }

  @Override
  protected DeadLetterPublisher deadLetterPublisher() {
    return deadLetterPublisher;
  }

  @Override
  protected boolean acceptsConfiguredTenantScope(
      WorkerConfiguration cfg, TaskDispatchMessage message) {
    if (Boolean.TRUE.equals(configuration.acceptCrossTenantDispatch())) {
      return true;
    }
    return super.acceptsConfiguredTenantScope(cfg, message);
  }

  @Override
  public String listenerId() {
    return "import-task-consumer";
  }
}
