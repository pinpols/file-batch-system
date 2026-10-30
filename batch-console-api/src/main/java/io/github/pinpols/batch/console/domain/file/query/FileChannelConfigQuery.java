package io.github.pinpols.batch.console.domain.file.query;

import io.github.pinpols.batch.common.model.PageRequest;
import lombok.Builder;

/** 绑定一次selectByQuery操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record FileChannelConfigQuery(
    String tenantId,
    String channelCode,
    String channelType,
    Boolean enabled,
    PageRequest pageRequest) {}
