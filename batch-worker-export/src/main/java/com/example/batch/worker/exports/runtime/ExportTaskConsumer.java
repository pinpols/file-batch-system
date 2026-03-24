package com.example.batch.worker.exports.runtime;

import com.example.batch.worker.core.application.TaskDispatchExecutor;
import com.example.batch.worker.core.config.WorkerConfiguration;
import com.example.batch.worker.core.support.AbstractTaskConsumer;
import com.example.batch.worker.core.support.AbstractWorkerLoop;
import com.example.batch.worker.exports.config.ExportWorkerConfiguration;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExportTaskConsumer extends AbstractTaskConsumer {

    private final ExportWorkerLoop workerLoop;
    private final ExportWorkerConfiguration configuration;
    private final TaskDispatchExecutor taskDispatchExecutor;

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

    @KafkaListener(topics = "#{__listener.topics()}", groupId = "#{__listener.consumerGroupId()}")
    public void consume(String payload) {
        doConsume(payload);
    }
}
