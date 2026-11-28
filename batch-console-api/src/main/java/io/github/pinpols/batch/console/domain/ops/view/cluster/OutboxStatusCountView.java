package io.github.pinpols.batch.console.domain.ops.view.cluster;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 实例 Outbox 状态、数量与时间范围的固定列投影。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutboxStatusCountView(String status, Long count, Instant oldest, Instant newest) {}
