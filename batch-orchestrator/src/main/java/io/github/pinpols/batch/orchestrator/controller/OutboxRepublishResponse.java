package io.github.pinpols.batch.orchestrator.controller;

/** Stable response contract for outbox republish operations. */
public record OutboxRepublishResponse(int requested, int reset, int dryRun) {}
