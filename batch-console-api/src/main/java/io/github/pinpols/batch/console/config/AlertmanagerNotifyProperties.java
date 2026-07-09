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
}
