package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.console.domain.notification.web.request.AlertmanagerAlert;
import io.github.pinpols.batch.console.domain.notification.web.request.AlertmanagerWebhookPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 把 Alertmanager webhook payload 渲染成人类可读的通知(标题 + 正文)+ 结构化字段。
 *
 * <p>无状态纯函数(单例 bean,并发共享安全):同一入参恒定产出同一结果,便于快照单测。多条 alert 分组合成一条摘要;缺字段全部容忍(用占位符),绝不抛异常。
 */
@Component
public class AlertmanagerAlertRenderer {

  static final String LABEL_ALERTNAME = "alertname";
  static final String LABEL_SEVERITY = "severity";
  static final String LABEL_INSTANCE = "instance";
  static final String ANNOTATION_SUMMARY = "summary";
  static final String ANNOTATION_DESCRIPTION = "description";
  private static final String PLACEHOLDER = "-";

  /** 渲染 payload。{@code maxAlerts} 限制正文逐条展开的告警数(防超大批量告警撑爆正文);超出部分折叠成 "... and N more"。 */
  public RenderedAlertNotification render(AlertmanagerWebhookPayload payload, int maxAlerts) {
    List<AlertmanagerAlert> alerts = payload.safeAlerts();
    String status = upper(orPlaceholder(payload.status()));
    String receiver = orPlaceholder(payload.receiver());
    String alertname = commonAlertname(payload);
    String severity = value(payload.commonLabels(), LABEL_SEVERITY);

    String title =
        "["
            + status
            + "] "
            + receiver
            + " · "
            + alertname
            + " ("
            + alerts.size()
            + " alert"
            + (alerts.size() == 1 ? "" : "s")
            + ")";

    StringBuilder body = new StringBuilder(title).append('\n');
    int shown = Math.min(alerts.size(), Math.max(0, maxAlerts));
    List<String> alertnames = new ArrayList<>();
    for (int i = 0; i < alerts.size(); i++) {
      AlertmanagerAlert alert = alerts.get(i);
      String name = orPlaceholder(alert.label(LABEL_ALERTNAME));
      alertnames.add(name);
      if (i >= shown) {
        continue;
      }
      body.append('\n')
          .append("- [")
          .append(upper(orPlaceholder(alert.status())))
          .append("] ")
          .append(name)
          .append(" severity=")
          .append(orPlaceholder(alert.label(LABEL_SEVERITY)))
          .append(" instance=")
          .append(orPlaceholder(alert.label(LABEL_INSTANCE)));
      String summary = alert.annotation(ANNOTATION_SUMMARY);
      if (hasText(summary)) {
        body.append("\n  summary: ").append(summary.strip());
      }
      String description = alert.annotation(ANNOTATION_DESCRIPTION);
      if (hasText(description)) {
        body.append("\n  description: ").append(description.strip());
      }
    }
    if (alerts.size() > shown) {
      body.append("\n... and ").append(alerts.size() - shown).append(" more");
    }

    Map<String, Object> structured = new LinkedHashMap<>();
    structured.put("receiver", payload.receiver());
    structured.put("status", payload.status());
    structured.put("alertCount", alerts.size());
    structured.put("truncatedAlerts", payload.truncatedAlerts());
    structured.put("severity", severity);
    structured.put("alertnames", alertnames);
    structured.put("text", body.toString());

    return new RenderedAlertNotification(title, body.toString(), structured);
  }

  private static String commonAlertname(AlertmanagerWebhookPayload payload) {
    String fromCommon = value(payload.commonLabels(), LABEL_ALERTNAME);
    if (!PLACEHOLDER.equals(fromCommon)) {
      return fromCommon;
    }
    return value(payload.groupLabels(), LABEL_ALERTNAME);
  }

  private static String value(Map<String, String> map, String key) {
    return orPlaceholder(map == null ? null : map.get(key));
  }

  private static String orPlaceholder(String value) {
    return hasText(value) ? value.strip() : PLACEHOLDER;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static String upper(String value) {
    return value == null ? PLACEHOLDER : value.toUpperCase(Locale.ROOT);
  }
}
