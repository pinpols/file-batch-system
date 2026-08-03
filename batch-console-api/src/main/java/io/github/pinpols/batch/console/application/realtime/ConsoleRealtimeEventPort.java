package io.github.pinpols.batch.console.application.realtime;

/**
 * 控制台实时变更发布端口。
 *
 * <p>领域服务只声明要发布的业务变更，具体的 Spring 事件、游标和 SSE 桥接由 observability 适配器负责。
 */
public interface ConsoleRealtimeEventPort {

  void publishChanged(String tenantId, String stream, String eventType);

  void publishChanged(String tenantId, String stream, String eventType, Object data);

  void publishSummaryRefresh(String tenantId);
}
