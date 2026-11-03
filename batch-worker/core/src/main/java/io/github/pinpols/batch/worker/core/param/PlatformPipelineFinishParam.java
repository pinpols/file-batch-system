package io.github.pinpols.batch.worker.core.param;

import java.time.OffsetDateTime;
import lombok.Builder;

/** Pipeline 终态写入参数；runStartedAt 是本次执行持有的运行代际，失配时语句不更新。 */
@Builder
public record PlatformPipelineFinishParam(
    Long pipelineInstanceId,
    OffsetDateTime runStartedAt,
    String currentStage,
    String lastSuccessStage,
    String runStatus) {}
