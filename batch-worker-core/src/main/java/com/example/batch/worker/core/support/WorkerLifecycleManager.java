package com.example.batch.worker.core.support;

import com.example.batch.worker.core.domain.WorkerRegistration;

public interface WorkerLifecycleManager {

    WorkerRegistration start(WorkerRegistration registration);

    void shutdown(String workerId);
}
