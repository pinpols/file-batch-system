package io.github.pinpols.batch.console.application.realtime;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 控制台实时订阅端口，连接生命周期和传输实现由 observability 适配器负责。 */
public interface ConsoleRealtimeSubscriptionPort {

  SseEmitter subscribe(
      String tenantId, String stream, String eventType, String cursor, Long heartbeatMillis);
}
