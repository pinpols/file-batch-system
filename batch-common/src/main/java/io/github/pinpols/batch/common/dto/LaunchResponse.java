package io.github.pinpols.batch.common.dto;

public record LaunchResponse(String instanceNo, String traceId) {

  public static LaunchResponse skipped(String traceId) {
    return new LaunchResponse(null, traceId);
  }
}
