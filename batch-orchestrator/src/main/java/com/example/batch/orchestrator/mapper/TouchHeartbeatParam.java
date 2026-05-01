package com.example.batch.orchestrator.mapper;

import lombok.Builder;
import lombok.Getter;

/**
 * 心跳更新参数。注意：{@code heartbeat_at} 字段由 mapper xml 直接用 DB {@code current_timestamp} 设置， 不再接收 Java
 * 层传入的时间戳，以消除 worker / orchestrator / DB 三方时钟漂移；本 param 不再持有该字段。
 */
@Getter
@Builder
public class TouchHeartbeatParam {
  private final String tenantId;
  private final String workerCode;
  private final String nextStatus;
  private final Integer currentLoad;
  private final String capabilityTags;
}
