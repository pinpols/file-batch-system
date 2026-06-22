package com.example.batch.worker.exports.runtime;

import com.example.batch.worker.core.application.TaskDispatchExecutor;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.infrastructure.DeadLetterPublisher;
import com.example.batch.worker.core.support.AbstractTaskConsumer;
import com.example.batch.worker.core.support.AbstractWorkerLoop;
import com.example.batch.worker.exports.config.ExportWorkerConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/** 导出任务 Kafka 消费者，监听导出任务 topic 并驱动任务执行。 */
@Service
public class ExportTaskConsumer extends AbstractTaskConsumer {

  private final ExportWorkerLoop workerLoop;
  private final ExportWorkerConfiguration configuration;
  private final TaskDispatchExecutor taskDispatchExecutor;
  private final DeadLetterPublisher deadLetterPublisher;

  public ExportTaskConsumer(
      ExportWorkerLoop workerLoop,
      ExportWorkerConfiguration configuration,
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
  protected String listenerId() {
    return "export-task-consumer";
  }

  /**
   * 消费 Kafka 消息并执行导出任务，成功处理后手动 ACK。
   *
   * @param payload 消息内容
   * @param acknowledgment 手动确认句柄
   */
  @KafkaListener(
      id = "export-task-consumer",
      topicPattern = "#{__listener.topicPattern()}",
      groupId = "#{__listener.consumerGroupId()}")
  public void consume(String payload, Acknowledgment acknowledgment) {
    if (doConsume(payload)) {
      acknowledgment.acknowledge();
    }
  }
}
