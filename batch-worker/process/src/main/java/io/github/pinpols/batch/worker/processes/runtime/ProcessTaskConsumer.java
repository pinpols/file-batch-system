package io.github.pinpols.batch.worker.processes.runtime;

import io.github.pinpols.batch.worker.core.application.TaskDispatchExecutor;
import io.github.pinpols.batch.worker.core.config.WorkerConfiguration;
import io.github.pinpols.batch.worker.core.infrastructure.DeadLetterPublisher;
import io.github.pinpols.batch.worker.core.support.AbstractTaskConsumer;
import io.github.pinpols.batch.worker.core.support.AbstractWorkerLoop;
import io.github.pinpols.batch.worker.processes.config.ProcessWorkerConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/** 加工任务 Kafka 消费者。 */
@Service
public class ProcessTaskConsumer extends AbstractTaskConsumer {

  private final ProcessWorkerLoop workerLoop;
  private final ProcessWorkerConfiguration configuration;
  private final TaskDispatchExecutor taskDispatchExecutor;
  private final DeadLetterPublisher deadLetterPublisher;

  public ProcessTaskConsumer(
      ProcessWorkerLoop workerLoop,
      ProcessWorkerConfiguration configuration,
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
    return "process-task-consumer";
  }

  @KafkaListener(
      id = "process-task-consumer",
      topicPattern = "#{__listener.topicPattern()}",
      groupId = "#{__listener.consumerGroupId()}")
  public void consume(String payload, Acknowledgment acknowledgment) {
    if (doConsume(payload)) {
      acknowledgment.acknowledge();
    }
  }
}
