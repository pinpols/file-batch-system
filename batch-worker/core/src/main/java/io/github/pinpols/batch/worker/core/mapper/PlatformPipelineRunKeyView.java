package io.github.pinpols.batch.worker.core.mapper;

import java.time.OffsetDateTime;

/** Pipeline 创建/复用后回传的行标识与本次运行代际。 */
public record PlatformPipelineRunKeyView(Long id, OffsetDateTime startedAt) {}
