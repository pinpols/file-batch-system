package io.github.pinpols.batch.console.domain.job.param;

import lombok.Builder;

/** 批次窗口更新参数；应用层先合并既有值，SQL仍按租户与主键限定。 */
@Builder
public record BatchWindowUpdateParam(
    String tenantId,
    Long id,
    String windowName,
    String timezone,
    String startTime,
    String endTime,
    String endStrategy,
    String outOfWindowAction,
    Boolean allowCrossDay,
    Boolean enabled,
    String description) {}
