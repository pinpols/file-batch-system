package com.example.batch.sdk.autoconfigure;

import com.example.batch.sdk.client.BatchPlatformClient;
import org.springframework.context.SmartLifecycle;

public final class BatchPlatformClientLifecycle implements SmartLifecycle {

  public static final int PHASE = Integer.MAX_VALUE - 100;

  private final BatchPlatformClient client;
  private final boolean autoStartup;
  private volatile boolean running;

  BatchPlatformClientLifecycle(BatchPlatformClient client, BatchWorkerSdkProperties properties) {
    this.client = client;
    this.autoStartup = properties.isEnabled();
  }

  @Override
  public void start() {
    if (running) {
      return;
    }
    client.start();
    running = true;
  }

  @Override
  public void stop() {
    if (!running) {
      return;
    }
    client.stop();
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isAutoStartup() {
    return autoStartup;
  }

  @Override
  public int getPhase() {
    return PHASE;
  }
}
