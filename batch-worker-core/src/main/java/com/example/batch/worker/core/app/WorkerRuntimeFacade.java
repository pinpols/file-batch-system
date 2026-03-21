package com.example.batch.worker.core.app;

import com.example.batch.worker.core.domain.PulledTask;
import com.example.batch.worker.core.domain.WorkerExecutionResult;
import com.example.batch.worker.core.domain.WorkerRegistration;
import com.example.batch.worker.core.support.HeartbeatService;
import com.example.batch.worker.core.support.TaskExecutionWrapper;
import com.example.batch.worker.core.support.WorkerLifecycleManager;
import org.springframework.stereotype.Service;

@Service
public class WorkerRuntimeFacade {

    private final WorkerLifecycleManager workerLifecycleManager;
    private final HeartbeatService heartbeatService;
    private final TaskExecutionWrapper taskExecutionWrapper;

    public WorkerRuntimeFacade(WorkerLifecycleManager workerLifecycleManager,
                               HeartbeatService heartbeatService,
                               TaskExecutionWrapper taskExecutionWrapper) {
        this.workerLifecycleManager = workerLifecycleManager;
        this.heartbeatService = heartbeatService;
        this.taskExecutionWrapper = taskExecutionWrapper;
    }

    public WorkerRegistration start(WorkerRegistration registration) {
        return workerLifecycleManager.start(registration);
    }

    public void heartbeat(String workerId) {
        heartbeatService.beat(workerId);
    }

    public void shutdown(String workerId) {
        workerLifecycleManager.shutdown(workerId);
    }

    public boolean claim(String tenantId, Long taskId, String workerId) {
        return taskExecutionWrapper.claim(tenantId, taskId, workerId);
    }

    public WorkerExecutionResult execute(PulledTask task) {
        return taskExecutionWrapper.execute(task);
    }
}
