package io.github.pinpols.batch.console.domain.observability.realtime;

/** 跨实例实时事件发布端口，隐藏 Redis Pub/Sub 与回放缓冲细节。 */
public interface RealtimeEventBus {

  void publish(ConsoleSseEvent event);
}
