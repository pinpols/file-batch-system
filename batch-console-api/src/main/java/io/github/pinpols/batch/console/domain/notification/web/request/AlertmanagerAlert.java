package io.github.pinpols.batch.console.domain.notification.web.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Alertmanager webhook payload 中的单条告警(typed,见 {@link AlertmanagerWebhookPayload})。
 *
 * <p>{@code startsAt}/{@code endsAt} 保持 String(RFC3339 原文,firing 时 endsAt 常为 {@code
 * 0001-01-01T00:00:00Z} 零值),仅用于展示,不解析为时间类型,避免 AM 版本间时间格式差异反序列化打红。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertmanagerAlert(
    String status,
    Map<String, String> labels,
    Map<String, String> annotations,
    String startsAt,
    String endsAt,
    String generatorURL,
    String fingerprint) {

  /** 从 labels 取值,空安全。 */
  public String label(String key) {
    return labels == null ? null : labels.get(key);
  }

  /** 从 annotations 取值,空安全。 */
  public String annotation(String key) {
    return annotations == null ? null : annotations.get(key);
  }
}
