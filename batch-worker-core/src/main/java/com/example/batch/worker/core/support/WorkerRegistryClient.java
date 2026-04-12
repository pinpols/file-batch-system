package com.example.batch.worker.core.support;

import com.example.batch.worker.core.domain.WorkerRegistration;

public interface WorkerRegistryClient {

  WorkerRegistration register(WorkerRegistration registration);

  WorkerRegistration heartbeat(WorkerRegistration registration);

  void deactivate(WorkerRegistration registration);

  WorkerRegistration updateStatus(WorkerRegistration registration);
}
