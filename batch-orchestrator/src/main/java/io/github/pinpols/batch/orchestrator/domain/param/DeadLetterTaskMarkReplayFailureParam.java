package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.Instant;
import lombok.Builder;

/** 绑定一次markReplayFailure操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record DeadLetterTaskMarkReplayFailureParam(
    String tenantId,
    Long id,
    String targetStatus,
    Integer replayCount,
    Instant lastReplayAt,
    String lastReplayResult,
    Instant nextReplayAt) {}
