package io.github.pinpols.batch.console.domain.notification.application;

import io.github.pinpols.batch.console.domain.notification.web.request.NotificationChannelUpdateRequest;
import io.github.pinpols.batch.console.domain.notification.web.request.NotificationChannelUpsertRequest;
import io.github.pinpols.batch.console.domain.notification.web.request.SubscriptionRuleUpsertRequest;
import java.util.List;
import java.util.Map;

/** 通知订阅管理应用服务：通知渠道 CRUD、订阅规则 CRUD、投递日志查询。 */
public interface ConsoleNotificationApplicationService {

  List<Map<String, Object>> listChannels(String tenantId);

  Map<String, Object> getChannel(String tenantId, String channelCode);

  void createChannel(String tenantId, NotificationChannelUpsertRequest request);

  void updateChannel(String tenantId, String channelCode, NotificationChannelUpdateRequest request);

  void deleteChannel(String tenantId, String channelCode);

  List<Map<String, Object>> listRules(String tenantId);

  Map<String, Object> getRule(String tenantId, Long ruleId);

  void createRule(String tenantId, SubscriptionRuleUpsertRequest request);

  void updateRule(String tenantId, Long ruleId, SubscriptionRuleUpsertRequest request);

  void deleteRule(String tenantId, Long ruleId);

  List<Map<String, Object>> deliveryLogs(String tenantId, int limit);

  Map<String, Object> testChannel(String tenantId, String channelCode);
}
