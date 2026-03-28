package com.example.batch.worker.imports.runtime;

import com.example.batch.worker.core.application.TaskDispatchExecutor;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.support.AbstractTaskConsumer;
import com.example.batch.worker.core.support.AbstractWorkerLoop;
import com.example.batch.worker.imports.config.ImportWorkerConfiguration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

@Service
public class ImportTaskConsumer extends AbstractTaskConsumer {

    private final ImportWorkerLoop workerLoop;
    private final ImportWorkerConfiguration configuration;
    private final TaskDispatchExecutor taskDispatchExecutor;

    public ImportTaskConsumer(ImportWorkerLoop workerLoop,
                             ImportWorkerConfiguration configuration,
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
        return "import-task-consumer";
    }

    @KafkaListener(id = "import-task-consumer", topics = "#{__listener.topics()}", groupId = "#{__listener.consumerGroupId()}")
    public void consume(String payload, Acknowledgment acknowledgment) {
        if (doConsume(payload)) {
            acknowledgment.acknowledge();
        }
    }
}
