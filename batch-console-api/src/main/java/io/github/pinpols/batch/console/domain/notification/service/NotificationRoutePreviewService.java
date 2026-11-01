package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.common.enums.AlertSeverity;
import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只预览规则命中；不读取或修改投递、去重和限流状态。 */
@Service
@RequiredArgsConstructor
public class NotificationRoutePreviewService {
  private static final Set<String> ALERT_SEVERITIES = DictEnum.codes(AlertSeverity.class);
  private final TenantIdResolver tenantGuard;
  private final SubscriptionRuleMapper rules;
  private final ConsoleWebhookSubscriptionMapper subscriptions;

  @Transactional(readOnly = true)
  public Preview preview(String tenantId, String eventType, String severity, String jobCode) {
    String tenant = tenantGuard.resolveTenant(tenantId);
    String event = eventType.trim().toUpperCase(Locale.ROOT);
    Map<String, Object> data = new HashMap<>();
    boolean jobEvent = "JOB_SUCCESS".equals(event) || "JOB_FAILED".equals(event);
    if (jobEvent && EmptyChecks.isBlank(jobCode)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.notification.preview_job_required");
    }
    // 与各生产者的实际载荷对齐：Job 终态有 jobCode，升级告警有 severity。
    if (jobEvent) data.put("jobCode", jobCode.trim());
    if ("ALERT_ESCALATED".equals(event)) {
      String normalizedSeverity =
          EmptyChecks.isNull(severity) ? "" : severity.trim().toUpperCase(Locale.ROOT);
      if (!ALERT_SEVERITIES.contains(normalizedSeverity)) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT, "error.notification.preview_severity_invalid");
      }
      data.put("severity", normalizedSeverity);
    }
    List<Rule> matchedRules = rules.selectEnabledByEventType(tenant, event).stream()
        .filter(rule -> SubscriptionRuleDispatchPolicy.matchesRule(rule, event, data))
        .map(rule -> new Rule(rule.id(), rule.ruleName(), rule.channelCode(), rule.channelType()))
        .toList();
    List<Webhook> matchedWebhooks = subscriptions.findEnabledByTenant(tenant).stream()
        .filter(subscription -> EmptyChecks.isNotNull(subscription.getId()))
        .filter(subscription ->
            SubscriptionRuleDispatchPolicy.eventTypeMatches(subscription.getEventTypes(), event))
        .map(subscription -> new Webhook(subscription.getId(), subscription.getName()))
        .toList();
    return new Preview(matchedRules, matchedWebhooks);
  }

  public record Preview(List<Rule> rules, List<Webhook> webhooks) {}

  public record Rule(Long id, String name, String channelCode, String channelType) {}

  public record Webhook(Long id, String name) {}
}
