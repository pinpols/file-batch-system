package com.example.batch.worker.dispatchs.runtime;

import com.example.batch.worker.core.application.TaskDispatchExecutor;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.support.AbstractTaskConsumer;
import com.example.batch.worker.core.support.AbstractWorkerLoop;
import com.example.batch.worker.dispatchs.config.DispatchWorkerConfiguration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class DispatchTaskConsumer extends AbstractTaskConsumer {

    private final DispatchWorkerLoop workerLoop;
    private final DispatchWorkerConfiguration configuration;
    private final TaskDispatchExecutor taskDispatchExecutor;

    public DispatchTaskConsumer(DispatchWorkerLoop workerLoop,
                               DispatchWorkerConfiguration configuration,
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
        return "dispatch-task-consumer";
    }

    @KafkaListener(id = "dispatch-task-consumer", topics = "#{__listener.topics()}", groupId = "#{__listener.consumerGroupId()}")
    public void consume(String payload) {
        doConsume(payload);
    }
}
