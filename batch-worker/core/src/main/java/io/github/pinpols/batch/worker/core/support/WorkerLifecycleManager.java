package io.github.pinpols.batch.worker.core.support;

import io.github.pinpols.batch.worker.core.domain.WorkerRegistration;

public interface WorkerLifecycleManager {

  WorkerRegistration start(WorkerRegistration registration);

  void shutdown(String workerId);
}
