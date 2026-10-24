package io.github.pinpols.batch.common.dto;

/** Result of accepting or submitting an exact scheduled occurrence for catch-up. */
public record ScheduledOccurrenceCatchUpResponse(String requestId, String status) {}
