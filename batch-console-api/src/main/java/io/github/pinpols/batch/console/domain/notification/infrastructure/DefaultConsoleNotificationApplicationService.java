package io.github.pinpols.batch.console.domain.notification.infrastructure;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.domain.notification.application.ConsoleNotificationApplicationService;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationChannelMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.domain.notification.web.request.NotificationChannelUpdateRequest;
import io.github.pinpols.batch.console.domain.notification.web.request.NotificationChannelUpsertRequest;
import io.github.pinpols.batch.console.domain.notification.web.request.SubscriptionRuleUpsertRequest;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
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
 * <p>唯一性约束：{@code channelCode} 应用层前置查重（{@code selectByCode != null → CONFLICT}）， 非 DB 唯一索引回退——并发创建同
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
  public void createChannel(String tenantId, NotificationChannelUpsertRequest request) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    String channelCode = request.getChannelCode();
    Guard.requireText(channelCode, "channelCode is required");
    Guard.requireText(request.getChannelName(), "channelName is required");
    String channelType = request.getChannelType();
    requireKnownChannelType(channelType);
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
            ConsoleTextSanitizer.safeInput(request.getChannelName(), 128),
            KEY_CHANNEL_TYPE,
            channelType,
            KEY_CONFIG_JSON,
            request.getConfigJson(),
            KEY_ENABLED,
            enabledOrDefault(request.getEnabled()),
            "createdBy",
            operator,
            KEY_UPDATED_BY,
            operator));
  }

  @Override
  @Transactional
  public void updateChannel(
      String tenantId, String channelCode, NotificationChannelUpdateRequest request) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Guard.requireFound(
        channelMapper.selectByCode(resolved, channelCode), ERR_CHANNEL_NOT_FOUND + channelCode);
    requireKnownChannelType(request.getChannelType());
    String operator = metadataResolver.current().operatorId();
    channelMapper.update(
        mapOf(
            KEY_TENANT_ID, resolved,
            KEY_CHANNEL_CODE, channelCode,
            KEY_CHANNEL_NAME, ConsoleTextSanitizer.safeInput(request.getChannelName(), 128),
            KEY_CHANNEL_TYPE, request.getChannelType(),
            KEY_CONFIG_JSON, request.getConfigJson(),
            KEY_ENABLED, enabledOrDefault(request.getEnabled()),
            KEY_UPDATED_BY, operator));
  }

  private static void requireKnownChannelType(String channelType) {
    if (!CHANNEL_TYPES.contains(channelType)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "channelType must be one of " + CHANNEL_TYPES);
    }
  }

  private static Boolean enabledOrDefault(Boolean enabled) {
    return enabled == null ? Boolean.TRUE : enabled;
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
  public void createRule(String tenantId, SubscriptionRuleUpsertRequest request) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    String channelCode = request.getChannelCode();
    Guard.requireFound(
        channelMapper.selectByCode(resolved, channelCode), ERR_CHANNEL_NOT_FOUND + channelCode);
    String operator = metadataResolver.current().operatorId();
    ruleMapper.insert(
        mapOf(
            KEY_TENANT_ID,
            resolved,
            KEY_RULE_NAME,
            ConsoleTextSanitizer.safeInput(request.getRuleName(), 128),
            KEY_CHANNEL_CODE,
            channelCode,
            KEY_EVENT_TYPES,
            ConsoleTextSanitizer.safeInput(request.getEventTypes(), 512),
            KEY_SEVERITY_FILTER,
            ConsoleTextSanitizer.safeInput(request.getSeverityFilter(), 128),
            KEY_JOB_CODE_FILTER,
            ConsoleTextSanitizer.safeInput(request.getJobCodeFilter(), 512),
            KEY_ENABLED,
            enabledOrDefault(request.getEnabled()),
            "createdBy",
            operator,
            KEY_UPDATED_BY,
            operator));
  }

  @Override
  @Transactional
  public void updateRule(String tenantId, Long ruleId, SubscriptionRuleUpsertRequest request) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Guard.requireFound(
        ruleMapper.selectById(resolved, ruleId), "subscription rule not found: " + ruleId);
    // P1-4: update 也必须校验 channel 存在(与 createRule 一致)。否则可写入失效 channelCode,
    // 而 SubscriptionRuleMapper.selectEnabledByEventType 要 join notification_channel,
    // 失效 channel 会让规则永不命中(保存成功但永远不生效的无效规则)。
    String channelCode = request.getChannelCode();
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
            ConsoleTextSanitizer.safeInput(request.getRuleName(), 128),
            KEY_CHANNEL_CODE,
            channelCode,
            KEY_EVENT_TYPES,
            ConsoleTextSanitizer.safeInput(request.getEventTypes(), 512),
            KEY_SEVERITY_FILTER,
            ConsoleTextSanitizer.safeInput(request.getSeverityFilter(), 128),
            KEY_JOB_CODE_FILTER,
            ConsoleTextSanitizer.safeInput(request.getJobCodeFilter(), 512),
            KEY_ENABLED,
            enabledOrDefault(request.getEnabled()),
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

  private static Map<String, Object> mapOf(Object... pairs) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      m.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return m;
  }
}
