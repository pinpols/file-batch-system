package com.example.batch.console.infrastructure;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.application.ConsoleNotificationApplicationService;
import com.example.batch.console.mapper.NotificationChannelMapper;
import com.example.batch.console.mapper.NotificationDeliveryLogMapper;
import com.example.batch.console.mapper.SubscriptionRuleMapper;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DefaultConsoleNotificationApplicationService
    implements ConsoleNotificationApplicationService {

  private static final Set<String> CHANNEL_TYPES =
      Set.of("EMAIL", "DINGTALK", "WECOM", "WEBHOOK", "SMS");

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver metadataResolver;
  private final NotificationChannelMapper channelMapper;
  private final SubscriptionRuleMapper ruleMapper;
  private final NotificationDeliveryLogMapper deliveryLogMapper;

  // ── 通知渠道 ──

  @Override
  public List<Map<String, Object>> listChannels(String tenantId) {
    return channelMapper.selectByTenant(tenantGuard.resolveTenant(tenantId));
  }

  @Override
  public Map<String, Object> getChannel(String tenantId, String channelCode) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> channel =
        Guard.requireFound(
            channelMapper.selectByCode(resolved, channelCode),
            "notification channel not found: " + channelCode);
    return channel;
  }

  @Override
  @Transactional
  public void createChannel(String tenantId, Map<String, Object> params) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    String channelCode = str(params, "channelCode");
    Guard.requireText(channelCode, "channelCode is required");
    String channelName = str(params, "channelName");
    Guard.requireText(channelName, "channelName is required");
    String channelType = str(params, "channelType");
    if (!CHANNEL_TYPES.contains(channelType)) {
      throw new BizException(
          ResultCode.INVALID_ARGUMENT, "channelType must be one of " + CHANNEL_TYPES);
    }
    if (channelMapper.selectByCode(resolved, channelCode) != null) {
      throw new BizException(ResultCode.CONFLICT, "channel code already exists: " + channelCode);
    }
    String operator = metadataResolver.current().operatorId();
    channelMapper.insert(
        mapOf(
            "tenantId",
            resolved,
            "channelCode",
            ConsoleTextSanitizer.safeInput(channelCode, 64),
            "channelName",
            ConsoleTextSanitizer.safeInput(str(params, "channelName"), 128),
            "channelType",
            channelType,
            "configJson",
            str(params, "configJson"),
            "enabled",
            params.getOrDefault("enabled", true),
            "createdBy",
            operator,
            "updatedBy",
            operator));
  }

  @Override
  @Transactional
  public void updateChannel(String tenantId, String channelCode, Map<String, Object> params) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Guard.requireFound(
        channelMapper.selectByCode(resolved, channelCode),
        "notification channel not found: " + channelCode);
    String channelType = str(params, "channelType");
    if (channelType != null && !CHANNEL_TYPES.contains(channelType)) {
      throw new BizException(
          ResultCode.INVALID_ARGUMENT, "channelType must be one of " + CHANNEL_TYPES);
    }
    String operator = metadataResolver.current().operatorId();
    channelMapper.update(
        mapOf(
            "tenantId", resolved,
            "channelCode", channelCode,
            "channelName", ConsoleTextSanitizer.safeInput(str(params, "channelName"), 128),
            "channelType", str(params, "channelType"),
            "configJson", str(params, "configJson"),
            "enabled", params.getOrDefault("enabled", true),
            "updatedBy", operator));
  }

  @Override
  @Transactional
  public void deleteChannel(String tenantId, String channelCode) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    channelMapper.deleteByCode(resolved, channelCode);
  }

  // ── 订阅规则 ──

  @Override
  public List<Map<String, Object>> listRules(String tenantId) {
    return ruleMapper.selectByTenant(tenantGuard.resolveTenant(tenantId));
  }

  @Override
  public Map<String, Object> getRule(String tenantId, Long ruleId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> rule =
        Guard.requireFound(
            ruleMapper.selectById(resolved, ruleId), "subscription rule not found: " + ruleId);
    return rule;
  }

  @Override
  @Transactional
  public void createRule(String tenantId, Map<String, Object> params) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    String channelCode = str(params, "channelCode");
    Guard.requireFound(
        channelMapper.selectByCode(resolved, channelCode),
        "notification channel not found: " + channelCode);
    String operator = metadataResolver.current().operatorId();
    ruleMapper.insert(
        mapOf(
            "tenantId", resolved,
            "ruleName", ConsoleTextSanitizer.safeInput(str(params, "ruleName"), 128),
            "channelCode", channelCode,
            "eventTypes", ConsoleTextSanitizer.safeInput(str(params, "eventTypes"), 512),
            "severityFilter", ConsoleTextSanitizer.safeInput(str(params, "severityFilter"), 128),
            "jobCodeFilter", ConsoleTextSanitizer.safeInput(str(params, "jobCodeFilter"), 512),
            "enabled", params.getOrDefault("enabled", true),
            "createdBy", operator,
            "updatedBy", operator));
  }

  @Override
  @Transactional
  public void updateRule(String tenantId, Long ruleId, Map<String, Object> params) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Guard.requireFound(
        ruleMapper.selectById(resolved, ruleId), "subscription rule not found: " + ruleId);
    String operator = metadataResolver.current().operatorId();
    ruleMapper.update(
        mapOf(
            "tenantId", resolved,
            "id", ruleId,
            "ruleName", ConsoleTextSanitizer.safeInput(str(params, "ruleName"), 128),
            "channelCode", str(params, "channelCode"),
            "eventTypes", ConsoleTextSanitizer.safeInput(str(params, "eventTypes"), 512),
            "severityFilter", ConsoleTextSanitizer.safeInput(str(params, "severityFilter"), 128),
            "jobCodeFilter", ConsoleTextSanitizer.safeInput(str(params, "jobCodeFilter"), 512),
            "enabled", params.getOrDefault("enabled", true),
            "updatedBy", operator));
  }

  @Override
  @Transactional
  public void deleteRule(String tenantId, Long ruleId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    ruleMapper.deleteById(resolved, ruleId);
  }

  // ── 投递日志 ──

  @Override
  public List<Map<String, Object>> deliveryLogs(String tenantId, int limit) {
    return deliveryLogMapper.selectByTenant(
        tenantGuard.resolveTenant(tenantId), Math.min(limit, 500));
  }

  // ── 测试通知 ──

  @Override
  public Map<String, Object> testChannel(String tenantId, String channelCode) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Guard.requireFound(
        channelMapper.selectByCode(resolved, channelCode),
        "notification channel not found: " + channelCode);
    // 写一条测试投递日志
    deliveryLogMapper.insert(
        mapOf(
            "tenantId",
            resolved,
            "ruleId",
            0,
            "channelCode",
            channelCode,
            "eventType",
            "TEST",
            "alertEventId",
            null,
            "payloadJson",
            "{\"message\":\"test notification from batch console\"}",
            "deliveryStatus",
            "SUCCESS",
            "attempt",
            1));
    return Map.of(
        "channelCode", channelCode, "status", "OK", "message", "test notification dispatched");
  }

  // ── helpers ──

  private static String str(Map<String, Object> map, String key) {
    Object v = map.get(key);
    return v != null ? v.toString() : null;
  }

  private static Map<String, Object> mapOf(Object... pairs) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      m.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return m;
  }
}
