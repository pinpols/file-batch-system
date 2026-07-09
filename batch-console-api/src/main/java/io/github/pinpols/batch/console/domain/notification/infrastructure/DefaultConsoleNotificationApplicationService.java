package io.github.pinpols.batch.console.domain.notification.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.notification.application.ConsoleNotificationApplicationService;
import io.github.pinpols.batch.console.domain.notification.entity.WebhookSubscriptionEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationChannelMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.NotificationDeliveryLogMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.domain.notification.service.NotificationMessage;
import io.github.pinpols.batch.console.domain.notification.service.NotificationSender;
import io.github.pinpols.batch.console.domain.notification.service.NotificationSenderRegistry;
import io.github.pinpols.batch.console.domain.notification.service.WebhookDeliveryResult;
import io.github.pinpols.batch.console.domain.notification.service.WebhookDispatcher;
import io.github.pinpols.batch.console.domain.notification.service.WebhookEventPayload;
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

  private static final String CHANNEL_TYPE_WEBHOOK = "WEBHOOK";
  private static final String COL_CHANNEL_TYPE = "channel_type";
  private static final String COL_CONFIG_JSON = "config_json";
  private static final String TEST_EVENT_TYPE = "TEST";

  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver metadataResolver;
  private final NotificationChannelMapper channelMapper;
  private final SubscriptionRuleMapper ruleMapper;
  private final NotificationDeliveryLogMapper deliveryLogMapper;
  private final NotificationSenderRegistry senderRegistry;
  private final WebhookDispatcher webhookDispatcher;

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

  /**
   * 渠道配置页「测试」按钮:按渠道类型真正发一条测试消息,如实反映投递结果并落真实投递日志。
   *
   * <p>WEBHOOK 渠道复用 {@link WebhookDispatcher#attemptDelivery}(自带 SSRF 防护 + 超时 + HMAC 签名);其余渠道
   * (EMAIL / DINGTALK / WECOM / SMS)经 {@link NotificationSenderRegistry} 解析对应 {@link
   * NotificationSender} 投递。 成功/失败均落一条 {@code notification_delivery_log};失败时把 sender
   * 的错误摘要透传给前端,便于运维排查配置。
   */
  @Override
  public Map<String, Object> testChannel(String tenantId, String channelCode) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> channel =
        Guard.requireFound(
            channelMapper.selectByCode(resolved, channelCode), ERR_CHANNEL_NOT_FOUND + channelCode);
    String channelType = str(channel, COL_CHANNEL_TYPE);
    String configJson = str(channel, COL_CONFIG_JSON);

    String testMessage = "[BATCH] 测试通知 " + BatchDateTimeSupport.utcNow();
    String payloadJson = JsonUtils.toJson(Map.of("message", testMessage, "test", Boolean.TRUE));
    WebhookEventPayload payload =
        new WebhookEventPayload(
            resolved,
            TEST_EVENT_TYPE,
            "notification-test",
            null,
            BatchDateTimeSupport.utcNow(),
            Map.of("message", testMessage));

    WebhookDeliveryResult result =
        deliverTest(resolved, channelCode, channelType, configJson, payload, payloadJson);

    // 真实投递结果落审计日志(成功 SUCCESS / 失败 FAILED + 错误摘要)。
    deliveryLogMapper.insert(
        mapOf(
            KEY_TENANT_ID,
            resolved,
            "ruleId",
            0,
            KEY_CHANNEL_CODE,
            channelCode,
            "eventType",
            TEST_EVENT_TYPE,
            "alertEventId",
            null,
            "payloadJson",
            payloadJson,
            "deliveryStatus",
            result.success() ? "SUCCESS" : "FAILED",
            "errorMessage",
            result.errorSummary(),
            "attempt",
            1));

    Map<String, Object> response = new LinkedHashMap<>();
    response.put(KEY_CHANNEL_CODE, channelCode);
    response.put(KEY_CHANNEL_TYPE, channelType);
    response.put("success", result.success());
    response.put("status", result.success() ? "OK" : "FAILED");
    response.put(
        "message",
        result.success()
            ? "test notification delivered"
            : "test notification failed: " + result.errorSummary());
    response.put("httpStatus", result.httpStatus());
    response.put("errorSummary", result.errorSummary());
    return response;
  }

  private WebhookDeliveryResult deliverTest(
      String tenantId,
      String channelCode,
      String channelType,
      String configJson,
      WebhookEventPayload payload,
      String payloadJson) {
    if (channelType == null || channelType.isBlank()) {
      return WebhookDeliveryResult.failure(null, "channel has no channel_type");
    }
    if (CHANNEL_TYPE_WEBHOOK.equalsIgnoreCase(channelType)) {
      WebhookSubscriptionEntity synthetic =
          toSyntheticWebhookSubscription(tenantId, channelCode, configJson);
      if (synthetic == null) {
        return WebhookDeliveryResult.failure(null, "webhook channel missing config url");
      }
      // attemptDelivery 复用生产投递路径:自带 DnsResolveGuard(SSRF)+ 请求超时,不会挂死请求线程。
      return webhookDispatcher.attemptDelivery(synthetic, payload, payloadJson);
    }
    NotificationSender sender = senderRegistry.resolve(channelType);
    if (sender == null) {
      return WebhookDeliveryResult.failure(
          null, "no sender registered for channel type: " + channelType);
    }
    return sender.send(
        new NotificationMessage(
            tenantId, channelCode, channelType, configJson, payload, payloadJson));
  }

  private WebhookSubscriptionEntity toSyntheticWebhookSubscription(
      String tenantId, String channelCode, String configJson) {
    Map<String, Object> config = parseConfig(configJson);
    String url = str(config, "url");
    if (url == null || url.isBlank()) {
      return null;
    }
    WebhookSubscriptionEntity entity = new WebhookSubscriptionEntity();
    entity.setTenantId(tenantId);
    entity.setName(channelCode);
    entity.setCallbackUrl(url);
    entity.setSecret(str(config, "secret"));
    entity.setEnabled(Boolean.TRUE);
    return entity;
  }

  private Map<String, Object> parseConfig(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, Object> parsed =
          JsonUtils.fromJson(configJson, new TypeReference<Map<String, Object>>() {});
      return parsed == null ? Map.of() : parsed;
    } catch (RuntimeException ex) {
      SwallowedExceptionLogger.info(
          DefaultConsoleNotificationApplicationService.class, "catch:config_json parse", ex);
      return Map.of();
    }
  }

  private static String str(Map<String, Object> map, String key) {
    Object value = map == null ? null : map.get(key);
    return value == null ? null : value.toString();
  }

  private static Map<String, Object> mapOf(Object... pairs) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      m.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return m;
  }
}
