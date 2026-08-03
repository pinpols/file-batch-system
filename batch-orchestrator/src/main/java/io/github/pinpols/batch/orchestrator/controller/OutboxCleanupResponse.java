package io.github.pinpols.batch.orchestrator.controller;

/** Stable response contract for outbox cleanup operations. */
public record OutboxCleanupResponse(int published, int giveUp) {}
