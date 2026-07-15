package io.github.pinpols.batch.console.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Alertmanager webhook 出口(sink)配置。
 *
 * <p>{@code /internal/am-notify/{receiver}} 端点接住 Alertmanager 的 webhook 回调,把告警投递到 {@code
 * notification_channel} 配置的真实渠道。该端点是内网端点(AM→console-api),AM 独立进程够不到 console 的
 * cookie/JWT,故用共享密钥(bearer token)鉴权。
 *
 * <p><b>fail-closed</b>:{@code bearerToken} 未配置(空)时,端点一律拒绝(401)—— 绝不裸奔。运维必须显式配置密钥并在 AM receiver 的
 * {@code http_config.authorization} 带同一 token。
 */
@Data
@ConfigurationProperties(prefix = "batch.console.alertmanager")
public class AlertmanagerNotifyProperties {

  /** 是否启用 AM 出口。false 时端点直接 503(功能关闭)。 */
  private boolean enabled = true;

  /** AM→console 共享密钥(bearer token)。空 = fail-closed,端点一律 401。 */
  private String bearerToken;

  /** AM 告警渠道归属租户(notification_channel 按租户维度存储);receiver 反查该租户下的 channel_code。 */
  private String tenantId = "system";

  /** 正文逐条展开的告警数上限,防超大批量告警撑爆正文;超出折叠成摘要。 */
  private int maxAlerts = 50;

  /**
   * console silence/close ↔ AM 单向桥接配置(迁移方案 §3.5)。
   *
   * <p>console silence 作用于单条 {@code alert_event.status=SUPPRESSED},AM silence 作用于 label matcher。 自研
   * notifier 退役后,console silence 若不桥接到 AM,只改 fbs 状态而 AM 照样 repeat 通知,语义破损。故 console silence 时由 fbs
   * 调 AM {@code POST /api/v2/silences} 建等价 matcher;close 时发 resolved(endsAt)。 反向(AM UI 建的 silence
   * 回写 console)不做。
   */
  private final Silence silence = new Silence();

  @Data
  public static class Silence {

    /** 是否启用 silence/close → AM 单向桥接。false = 关闭桥接(只改 fbs 状态)。 */
    private boolean enabled = true;

    /** AM base URL(不含路径);桥接追加 {@code /api/v2/silences} 或 {@code /api/v2/alerts}。 */
    private String apiBaseUrl = "http://localhost:9093";

    /** silence/resolved 推送 HTTP 读超时(毫秒)。 */
    private long timeoutMillis = 2000L;

    /** 连接超时(毫秒)。 */
    private long connectTimeoutMillis = 2000L;

    /** console silence 未显式给时长时的默认静默时长(分钟)。 */
    private int defaultDurationMinutes = 120;
  }
}
