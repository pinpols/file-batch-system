package io.github.pinpols.batch.console.domain.notification.mapper;

import io.github.pinpols.batch.console.domain.notification.param.WorkflowTerminalNotificationClaimParam;
import io.github.pinpols.batch.console.domain.notification.param.WorkflowTerminalNotificationFinishParam;
import io.github.pinpols.batch.console.domain.notification.param.WorkflowTerminalNotificationInsertRuleParam;
import io.github.pinpols.batch.console.domain.notification.param.WorkflowTerminalNotificationInsertWebhookParam;
import io.github.pinpols.batch.console.domain.notification.view.TerminalNotificationDelivery;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowTerminalNotificationMapper {
  String bindTenant(@Param("tenantId") String tenantId);

  boolean lockReceipt(@Param("tenantId") String tenantId, @Param("eventKey") String eventKey);

  int insertReceipt(
      @Param("tenantId") String tenantId,
      @Param("eventKey") String eventKey,
      @Param("payload") String payload);

  String receiptPayload(@Param("tenantId") String tenantId, @Param("eventKey") String eventKey);

  int insertWebhook(WorkflowTerminalNotificationInsertWebhookParam parameters);

  int insertRule(WorkflowTerminalNotificationInsertRuleParam parameters);

  List<TerminalNotificationDelivery> pending(@Param("now") Instant now, @Param("limit") int limit);

  int claim(WorkflowTerminalNotificationClaimParam parameters);

  int finish(WorkflowTerminalNotificationFinishParam parameters);
}
