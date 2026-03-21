package com.example.batch.worker.core.support;

import com.example.batch.worker.core.domain.WorkerRegistration;

public interface WorkerRegistryService {

    WorkerRegistration register(WorkerRegistration registration);

    WorkerRegistration renew(WorkerRegistration registration);

    void deactivate(WorkerRegistration registration);

    WorkerRegistration updateStatus(WorkerRegistration registration, String status);
}
