package io.github.pinpols.batch.console.application.ops;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 运维摘要实时订阅端口，SSE 连接生命周期由观测适配器负责。 */
public interface ConsoleOpsSummaryRealtimePort {

  /** 订阅指定租户的运维摘要事件。 */
  SseEmitter subscribe(String tenantId, Long heartbeatMillis, boolean initialSnapshot);
}
