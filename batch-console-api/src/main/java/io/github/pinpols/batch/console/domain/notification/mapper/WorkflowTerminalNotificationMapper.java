package io.github.pinpols.batch.console.domain.notification.mapper;

import io.github.pinpols.batch.console.domain.notification.entity.TerminalNotificationDelivery;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowTerminalNotificationMapper {
  int insertReceipt(@Param("tenantId") String tenantId, @Param("eventKey") String eventKey,
      @Param("payload") String payload);
  String receiptPayload(@Param("tenantId") String tenantId, @Param("eventKey") String eventKey);
  int insertWebhook(@Param("tenantId") String tenantId, @Param("recipientId") Long recipientId,
      @Param("eventKey") String eventKey, @Param("eventType") String eventType,
      @Param("payload") String payload);
  int insertRule(@Param("tenantId") String tenantId, @Param("recipientId") Long recipientId,
      @Param("channelCode") String channelCode, @Param("eventKey") String eventKey,
      @Param("eventType") String eventType, @Param("payload") String payload);
  List<TerminalNotificationDelivery> pending(@Param("now") Instant now, @Param("limit") int limit);
  int claim(@Param("row") TerminalNotificationDelivery row, @Param("token") String token,
      @Param("now") Instant now, @Param("until") Instant until, @Param("maxAttempts") int maxAttempts);
  int finish(@Param("row") TerminalNotificationDelivery row, @Param("token") String token,
      @Param("status") String status, @Param("error") String error,
      @Param("nextRetry") Instant nextRetry);
}
