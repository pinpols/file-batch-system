package io.github.pinpols.batch.console.domain.notification.web.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Alertmanager webhook v4 回调 payload 的 typed 契约(不用 Map,遵循本仓 Map-body 契约漂移教训)。
 *
 * <p>字段对齐 Alertmanager 官方 webhook
 * 结构(https://prometheus.io/docs/alerting/latest/configuration/#webhook_config 的 v4
 * 版本)。这是一个稳定的公开契约,故建 record 显式绑定;{@code labels}/{@code annotations} 是运行期动态标签集, 天然为 {@code
 * Map<String,String>}(非无类型 body 反模式)。
 *
 * <p>{@link JsonIgnoreProperties} 宽容忽略未来 AM 版本新增字段,避免上游升级把已建告警反序列化打红。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertmanagerWebhookPayload(
    String version,
    String groupKey,
    Integer truncatedAlerts,
    String status,
    String receiver,
    Map<String, String> groupLabels,
    Map<String, String> commonLabels,
    Map<String, String> commonAnnotations,
    String externalURL,
    List<AlertmanagerAlert> alerts) {

  /** 空安全:返回不可变的 alerts 列表,永不为 null。 */
  public List<AlertmanagerAlert> safeAlerts() {
    return alerts == null ? List.of() : alerts;
  }
}
