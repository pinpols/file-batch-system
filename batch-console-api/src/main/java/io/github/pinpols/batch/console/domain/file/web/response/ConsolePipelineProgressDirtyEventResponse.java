package io.github.pinpols.batch.console.domain.file.web.response;

import java.time.Instant;

/** Pipeline 进度 dirty 事件载荷；只承载刷新线索，不承载行级进度明细。 */
public record ConsolePipelineProgressDirtyEventResponse(
    String tenantId,
    Long pipelineInstanceId,
    Long jobInstanceId,
    String reason,
    Long version,
    Instant updatedAt) {}
