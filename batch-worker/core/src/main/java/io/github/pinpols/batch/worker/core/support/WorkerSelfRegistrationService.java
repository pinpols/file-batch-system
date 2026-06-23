package io.github.pinpols.batch.worker.core.support;

import io.github.pinpols.batch.worker.core.domain.WorkerRegistration;

public interface WorkerSelfRegistrationService {

  WorkerRegistration register(WorkerRegistration registration);

  WorkerRegistration renew(WorkerRegistration registration);

  void deactivate(WorkerRegistration registration);

  WorkerRegistration updateStatus(WorkerRegistration registration, String status);
}
