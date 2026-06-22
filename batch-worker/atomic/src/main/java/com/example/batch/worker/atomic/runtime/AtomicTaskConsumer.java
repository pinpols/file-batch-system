package com.example.batch.worker.atomic.runtime;

import com.example.batch.worker.atomic.config.AtomicWorkerConfiguration;
import com.example.batch.worker.core.application.TaskDispatchExecutor;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.infrastructure.DeadLetterPublisher;
import com.example.batch.worker.core.support.AbstractTaskConsumer;
import com.example.batch.worker.core.support.AbstractWorkerLoop;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/** 专用原子任务 worker 的 Kafka 消费者:消费 batch.task.dispatch.atomic,执行 shell/sql/stored-proc/http 原子任务。 */
@Service
public class AtomicTaskConsumer extends AbstractTaskConsumer {

  private final AtomicWorkerLoop workerLoop;
  private final AtomicWorkerConfiguration configuration;
  private final TaskDispatchExecutor taskDispatchExecutor;
  private final DeadLetterPublisher deadLetterPublisher;

  public AtomicTaskConsumer(
      AtomicWorkerLoop workerLoop,
      AtomicWorkerConfiguration configuration,
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
    return "spi-task-consumer";
  }

  @KafkaListener(
      id = "spi-task-consumer",
      topicPattern = "#{__listener.topicPattern()}",
      groupId = "#{__listener.consumerGroupId()}")
  public void consume(String payload, Acknowledgment acknowledgment) {
    if (doConsume(payload)) {
      acknowledgment.acknowledge();
    }
  }
}
