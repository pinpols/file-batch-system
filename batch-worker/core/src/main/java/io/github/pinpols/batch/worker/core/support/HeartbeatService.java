package io.github.pinpols.batch.worker.core.support;

public interface HeartbeatService {

  void beat(String workerId);
}
