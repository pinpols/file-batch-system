package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.Instant;

/** 重试未绑定执行的失败条目，目标状态由 Mapper 的语义入口提供。 */
public record BatchDayReplayEntryRetryParam(
    String tenantId, Long sessionId, Long id, Instant now, String targetStatus) {}
