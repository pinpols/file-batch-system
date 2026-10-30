package io.github.pinpols.batch.console.domain.param;

import java.time.Instant;
import lombok.Builder;

/** 新配置发布草稿的固定写入字段；版本、载荷和操作者由应用服务提供。 */
@Builder
public record ConfigReleaseInsertParam(
    String tenantId,
    String configType,
    String configKey,
    String configName,
    String configStatus,
    Integer versionNo,
    String grayScopeJson,
    String configPayloadJson,
    Instant effectiveFromAt,
    Instant effectiveToAt,
    String createdBy,
    String updatedBy) {}
