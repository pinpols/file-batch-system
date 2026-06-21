package com.example.batch.console.domain.notification.infrastructure;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.domain.notification.application.ConsoleNotificationApplicationService;
import com.example.batch.console.domain.notification.mapper.NotificationChannelMapper;
import com.example.batch.console.domain.notification.mapper.NotificationDeliveryLogMapper;
import com.example.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通知治理服务：管理通知渠道 / 订阅规则 / 投递日志三张表的 CRUD 与查询。
 *
 * <p>3 块职责：
 *
 * <ul>
 *   <li><b>通知渠道</b>（EMAIL / DINGTALK / WECOM / WEBHOOK / SMS）：{@link #CHANNEL_TYPES} 是白名单枚举，
 *       创建/更新时入参 {@code channelType} 必须在集合内，否则 {@code INVALID_ARGUMENT} 拒绝。
 *   <li><b>订阅规则</b>：按租户订阅 (eventType, severity, jobCode) 维度的告警，分发到指定渠道。
 *   <li><b>投递日志</b>：审计用，每次通知分发的结果（成功/失败）都在此记录。
 * </ul>
 *
 * <p>唯一性约束：{@code channelCode} 应用层前置查重（{@code selectByCode != null → CONFLICT}）， 非 DB 唯一索引兜底——并发创建同
 * code 时存在 TOCTOU 窗口，但通知治理操作并发度低，可接受。
 *
 * <p>自由文本入参（channelName / channelCode 等）统一经 {@link ConsoleTextSanitizer#safeInput} 截断清洗。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleNotificationApplicationService
    implements ConsoleNotificationApplicationService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String ERR_CHANNEL_NOT_FOUND = "notification channel not found: ";
  private static final String KEY_CONFIG_JSON = "configJson";
  private static final String KEY_UPDATED_BY = "updatedBy";
  private static final String KEY_RULE_NAME = "ruleName";
  private static final String KEY_EVENT_TYPES = "eventTypes";
  private static final String KEY_SEVERITY_FILTER = "severityFilter";
  private static final String KEY_JOB_CODE_FILTER = "jobCodeFilter";
  private static final String KEY_CHANNEL_CODE = "channelCode";
  private static final String KEY_ENABLED = "enabled";
  private static final String KEY_CHANNEL_NAME = "channelName";
  private static final String KEY_CHANNEL_TYPE = "channelType";
  private static final String KEY_TENANT_ID = "tenantId";

  private static final Set<String> CHANNEL_TYPES =
      Set.of("EMAIL", "DINGTALK", "WECOM", "WEBHOOK", "SMS");

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver metadataResolver;
  private final NotificationChannelMapper channelMapper;
  private final SubscriptionRuleMapper ruleMapper;
  private final NotificationDeliveryLogMapper deliveryLogMapper;

  @Override
  public List<Map<String, Object>> listChannels(String tenantId) {
    return channelMapper.selectByTenant(tenantGuard.resolveTenant(tenantId));
  }

  @Override
  public Map<String, Object> getChannel(String tenantId, String channelCode) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> channel =
        Guard.requireFound(
            channelMapper.selectByCode(resolved, channelCode), ERR_CHANNEL_NOT_FOUND + channelCode);
    return channel;
  }

  @Override
  @Transactional
  public void createChannel(String tenantId, Map<String, Object> params) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    String channelCode = str(params, KEY_CHANNEL_CODE);
    Guard.requireText(channelCode, "channelCode is required");
    String channelName = str(params, KEY_CHANNEL_NAME);
    Guard.requireText(channelName, "channelName is required");
    String channelType = str(params, KEY_CHANNEL_TYPE);
    if (!CHANNEL_TYPES.contains(channelType)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "channelType must be one of " + CHANNEL_TYPES);
    }
    if (channelMapper.selectByCode(resolved, channelCode) != null) {
      throw BizException.of(
          ResultCode.CONFLICT, "error.file_channel.code_already_exists", channelCode);
    }
    String operator = metadataResolver.current().operatorId();
    channelMapper.insert(
        mapOf(
            KEY_TENANT_ID,
            resolved,
            KEY_CHANNEL_CODE,
            ConsoleTextSanitizer.safeInput(channelCode, 64),
            KEY_CHANNEL_NAME,
            ConsoleTextSanitizer.safeInput(str(params, KEY_CHANNEL_NAME), 128),
            KEY_CHANNEL_TYPE,
            channelType,
            KEY_CONFIG_JSON,
            str(params, KEY_CONFIG_JSON),
            KEY_ENABLED,
            params.getOrDefault(KEY_ENABLED, true),
            "createdBy",
            operator,
            KEY_UPDATED_BY,
            operator));
  }

  @Override
  @Transactional
  public void updateChannel(String tenantId, String channelCode, Map<String, Object> params) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Guard.requireFound(
        channelMapper.selectByCode(resolved, channelCode), ERR_CHANNEL_NOT_FOUND + channelCode);
    String channelType = str(params, KEY_CHANNEL_TYPE);
    if (channelType != null && !CHANNEL_TYPES.contains(channelType)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "channelType must be one of " + CHANNEL_TYPES);
    }
    String operator = metadataResolver.current().operatorId();
    channelMapper.update(
        mapOf(
            KEY_TENANT_ID, resolved,
            KEY_CHANNEL_CODE, channelCode,
            KEY_CHANNEL_NAME, ConsoleTextSanitizer.safeInput(str(params, KEY_CHANNEL_NAME), 128),
            KEY_CHANNEL_TYPE, str(params, KEY_CHANNEL_TYPE),
            KEY_CONFIG_JSON, str(params, KEY_CONFIG_JSON),
            KEY_ENABLED, params.getOrDefault(KEY_ENABLED, true),
            KEY_UPDATED_BY, operator));
  }

  @Override
  @Transactional
  public void deleteChannel(String tenantId, String channelCode) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    channelMapper.deleteByCode(resolved, channelCode);
  }

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
    String channelCode = str(params, KEY_CHANNEL_CODE);
    Guard.requireFound(
        channelMapper.selectByCode(resolved, channelCode), ERR_CHANNEL_NOT_FOUND + channelCode);
    String operator = metadataResolver.current().operatorId();
    ruleMapper.insert(
        mapOf(
            KEY_TENANT_ID,
            resolved,
            KEY_RULE_NAME,
            ConsoleTextSanitizer.safeInput(str(params, KEY_RULE_NAME), 128),
            KEY_CHANNEL_CODE,
            channelCode,
            KEY_EVENT_TYPES,
            ConsoleTextSanitizer.safeInput(str(params, KEY_EVENT_TYPES), 512),
            KEY_SEVERITY_FILTER,
            ConsoleTextSanitizer.safeInput(str(params, KEY_SEVERITY_FILTER), 128),
            KEY_JOB_CODE_FILTER,
            ConsoleTextSanitizer.safeInput(str(params, KEY_JOB_CODE_FILTER), 512),
            KEY_ENABLED,
            params.getOrDefault(KEY_ENABLED, true),
            "createdBy",
            operator,
            KEY_UPDATED_BY,
            operator));
  }

  @Override
  @Transactional
  public void updateRule(String tenantId, Long ruleId, Map<String, Object> params) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Guard.requireFound(
        ruleMapper.selectById(resolved, ruleId), "subscription rule not found: " + ruleId);
    // P1-4: update 也必须校验 channel 存在(与 createRule 一致)。否则可写入失效 channelCode,
    // 而 SubscriptionRuleMapper.selectEnabledByEventType 要 join notification_channel,
    // 失效 channel 会让规则永不命中(保存成功但永远不生效的无效规则)。
    String channelCode = str(params, KEY_CHANNEL_CODE);
    Guard.requireFound(
        channelMapper.selectByCode(resolved, channelCode), ERR_CHANNEL_NOT_FOUND + channelCode);
    String operator = metadataResolver.current().operatorId();
    ruleMapper.update(
        mapOf(
            KEY_TENANT_ID,
            resolved,
            "id",
            ruleId,
            KEY_RULE_NAME,
            ConsoleTextSanitizer.safeInput(str(params, KEY_RULE_NAME), 128),
            KEY_CHANNEL_CODE,
            channelCode,
            KEY_EVENT_TYPES,
            ConsoleTextSanitizer.safeInput(str(params, KEY_EVENT_TYPES), 512),
            KEY_SEVERITY_FILTER,
            ConsoleTextSanitizer.safeInput(str(params, KEY_SEVERITY_FILTER), 128),
            KEY_JOB_CODE_FILTER,
            ConsoleTextSanitizer.safeInput(str(params, KEY_JOB_CODE_FILTER), 512),
            KEY_ENABLED,
            params.getOrDefault(KEY_ENABLED, true),
            KEY_UPDATED_BY,
            operator));
  }

  @Override
  @Transactional
  public void deleteRule(String tenantId, Long ruleId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    ruleMapper.deleteById(resolved, ruleId);
  }

  @Override
  public List<Map<String, Object>> deliveryLogs(String tenantId, int limit) {
    return deliveryLogMapper.selectByTenant(
        tenantGuard.resolveTenant(tenantId), Math.min(limit, 500));
  }

  @Override
  public Map<String, Object> testChannel(String tenantId, String channelCode) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Guard.requireFound(
        channelMapper.selectByCode(resolved, channelCode), ERR_CHANNEL_NOT_FOUND + channelCode);
    // 写一条测试投递日志
    deliveryLogMapper.insert(
        mapOf(
            KEY_TENANT_ID,
            resolved,
            "ruleId",
            0,
            KEY_CHANNEL_CODE,
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
        KEY_CHANNEL_CODE, channelCode, "status", "OK", "message", "test notification dispatched");
  }

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
