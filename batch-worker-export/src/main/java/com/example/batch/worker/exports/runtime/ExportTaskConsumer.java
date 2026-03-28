package com.example.batch.worker.exports.runtime;

import com.example.batch.worker.core.application.TaskDispatchExecutor;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.support.AbstractTaskConsumer;
import com.example.batch.worker.core.support.AbstractWorkerLoop;
import com.example.batch.worker.exports.config.ExportWorkerConfiguration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Service
public class ExportTaskConsumer extends AbstractTaskConsumer {

    private final ExportWorkerLoop workerLoop;
    private final ExportWorkerConfiguration configuration;
    private final TaskDispatchExecutor taskDispatchExecutor;

    public ExportTaskConsumer(ExportWorkerLoop workerLoop,
                             ExportWorkerConfiguration configuration,
                             TaskDispatchExecutor taskDispatchExecutor,
                             KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry) {
        super(kafkaListenerEndpointRegistry);
        this.workerLoop = workerLoop;
        this.configuration = configuration;
        this.taskDispatchExecutor = taskDispatchExecutor;
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
    protected String listenerId() {
        return "export-task-consumer";
    }

    @KafkaListener(id = "export-task-consumer", topics = "#{__listener.topics()}", groupId = "#{__listener.consumerGroupId()}")
    public void consume(String payload, Acknowledgment acknowledgment) {
        if (doConsume(payload)) {
            acknowledgment.acknowledge();
        }
    }
}
