package io.github.pinpols.batch.console.domain.file.view;

import lombok.Builder;

import java.time.Instant;
/** 文件渠道列表查询的固定列投影；JSONB 配置保持数据库驱动返回值，避免改变复制语义。 */
@Builder
public record FileChannelConfigQueryView(
    Long id,
    String tenantId,
    String channelCode,
    String channelName,
    String channelType,
    String targetEndpoint,
    String authType,
    Object configJson,
    String receiptPolicy,
    Integer timeoutSeconds,
    Boolean enabled,
    Instant createdAt,
    Instant updatedAt) {}
