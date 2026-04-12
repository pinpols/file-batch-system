package com.example.batch.console.application;

import java.util.List;
import java.util.Map;

/** 通知订阅管理应用服务：通知渠道 CRUD、订阅规则 CRUD、投递日志查询。 */
public interface ConsoleNotificationApplicationService {

  // ── 通知渠道 ──

  List<Map<String, Object>> listChannels(String tenantId);

  Map<String, Object> getChannel(String tenantId, String channelCode);

  void createChannel(String tenantId, Map<String, Object> params);

  void updateChannel(String tenantId, String channelCode, Map<String, Object> params);

  void deleteChannel(String tenantId, String channelCode);

  // ── 订阅规则 ──

  List<Map<String, Object>> listRules(String tenantId);

  Map<String, Object> getRule(String tenantId, Long ruleId);

  void createRule(String tenantId, Map<String, Object> params);

  void updateRule(String tenantId, Long ruleId, Map<String, Object> params);

  void deleteRule(String tenantId, Long ruleId);

  // ── 投递日志 ──

  List<Map<String, Object>> deliveryLogs(String tenantId, int limit);

  // ── 测试通知 ──

  Map<String, Object> testChannel(String tenantId, String channelCode);
}
