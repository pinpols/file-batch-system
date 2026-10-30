package io.github.pinpols.batch.trigger.support;

import java.time.Instant;

/** 同一原始 fire 首次持久化的 readiness 等待窗口。 */
public record ReadinessWaitWindow(Instant deferredSince, Instant deadline) {}
