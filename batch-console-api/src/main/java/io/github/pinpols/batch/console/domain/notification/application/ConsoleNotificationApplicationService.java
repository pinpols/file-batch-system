package io.github.pinpols.batch.console.domain.notification.application;

import io.github.pinpols.batch.console.domain.notification.web.request.NotificationChannelUpdateRequest;
import io.github.pinpols.batch.console.domain.notification.web.request.NotificationChannelUpsertRequest;
import io.github.pinpols.batch.console.domain.notification.web.request.SubscriptionRuleUpsertRequest;
import io.github.pinpols.batch.console.domain.notification.web.response.ConsoleNotificationChannelResponse;
import io.github.pinpols.batch.console.domain.notification.web.response.ConsoleNotificationDeliveryLogResponse;
import io.github.pinpols.batch.console.domain.notification.web.response.ConsoleNotificationTestResultResponse;
import io.github.pinpols.batch.console.domain.notification.web.response.ConsoleSubscriptionRuleResponse;
import java.util.List;

/** 通知订阅管理应用服务：通知渠道 CRUD、订阅规则 CRUD、投递日志查询。 */
public interface ConsoleNotificationApplicationService {

  List<ConsoleNotificationChannelResponse> listChannels(String tenantId);

  ConsoleNotificationChannelResponse getChannel(String tenantId, String channelCode);

  void createChannel(String tenantId, NotificationChannelUpsertRequest request);

  void updateChannel(String tenantId, String channelCode, NotificationChannelUpdateRequest request);

  void deleteChannel(String tenantId, String channelCode);

  List<ConsoleSubscriptionRuleResponse> listRules(String tenantId);

  ConsoleSubscriptionRuleResponse getRule(String tenantId, Long ruleId);

  void createRule(String tenantId, SubscriptionRuleUpsertRequest request);

  void updateRule(String tenantId, Long ruleId, SubscriptionRuleUpsertRequest request);

  void deleteRule(String tenantId, Long ruleId);

  List<ConsoleNotificationDeliveryLogResponse> deliveryLogs(String tenantId, int limit);

  ConsoleNotificationTestResultResponse testChannel(String tenantId, String channelCode);
}
