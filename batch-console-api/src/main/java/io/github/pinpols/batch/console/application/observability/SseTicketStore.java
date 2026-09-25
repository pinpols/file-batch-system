package io.github.pinpols.batch.console.application.observability;

import java.time.Duration;

/** SSE 一次性 ticket 存储端口。 */
public interface SseTicketStore {

  void save(String ticket, String value, Duration ttl);

  String consume(String ticket);
}
