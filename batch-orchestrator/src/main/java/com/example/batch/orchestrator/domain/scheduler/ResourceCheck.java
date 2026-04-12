package com.example.batch.orchestrator.domain.scheduler;

public record ResourceCheck(
    boolean allowed, boolean failFast, String reasonCode, String reasonMessage) {
  public static ResourceCheck allow() {
    return new ResourceCheck(true, false, null, null);
  }

  public static ResourceCheck waitForCapacity(String reasonCode, String reasonMessage) {
    return new ResourceCheck(false, false, reasonCode, reasonMessage);
  }

  public static ResourceCheck reject(String reasonCode, String reasonMessage) {
    return new ResourceCheck(false, true, reasonCode, reasonMessage);
  }
}
