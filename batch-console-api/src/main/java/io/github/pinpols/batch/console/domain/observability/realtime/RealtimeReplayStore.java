package io.github.pinpols.batch.console.domain.observability.realtime;

import java.util.List;

/** SSE 断线回放存储端口，业务层只关心追加与游标回放语义。 */
public interface RealtimeReplayStore {

  void append(ConsoleRealtimeStreamEnvelope envelope);

  ReplayBatch replay(String tenantId, String stream, String afterCursor, String eventType);

  record ReplayBatch(List<ConsoleRealtimeStreamEnvelope> events, boolean cursorFound) {}
}
