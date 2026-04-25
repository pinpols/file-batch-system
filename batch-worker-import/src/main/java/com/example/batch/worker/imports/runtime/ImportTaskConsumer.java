package com.example.batch.worker.imports.runtime;

import com.example.batch.worker.core.application.TaskDispatchExecutor;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.infrastructure.DeadLetterPublisher;
import com.example.batch.worker.core.support.AbstractTaskConsumer;
import com.example.batch.worker.core.support.AbstractWorkerLoop;
import com.example.batch.worker.imports.config.ImportWorkerConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/**
 * Import Worker 的 Kafka 消费者：监听 {@code topics()} 主题，将消息委托给
 * {@link AbstractTaskConsumer#doConsume} 处理（CLAIM → EXECUTE → REPORT 链路）。
 *
 * <p>消费成功（{@code doConsume} 返回 true）后手动提交 offset；
 * {@code listenerId} 为 {@code "import-task-consumer"}，与 ShedLock / 指标标签一致。
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
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    super(kafkaListenerEndpointRegistry, meterRegistryProvider);
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
  protected String listenerId() {
    return "import-task-consumer";
  }

  @KafkaListener(
      id = "import-task-consumer",
      topicPattern = "#{__listener.topicPattern()}",
      groupId = "#{__listener.consumerGroupId()}")
  public void consume(String payload, Acknowledgment acknowledgment) {
    if (doConsume(payload)) {
      acknowledgment.acknowledge();
    }
  }
}
