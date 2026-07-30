package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * emit 直连 Alertmanager 的配置（迁移方案 §6.2，direct cutover）。
 *
 * <p>{@code enabled} 是全局开关，由部署配置显式控制；默认关闭且未配置 endpoint 时只落库不推 AM， 避免开发机或基础 Helm values
 * 意外访问外部告警系统。生产启用时通过受管环境变量或 Helm overlay 注入。 不设 tenant/alert-type allowlist（回滚粒度就是全局开关）。
 */
@Data
@ConfigurationProperties(prefix = "batch.alert.am-emit")
public class AlertmanagerEmitProperties {

  /** 是否把 emit 落库后的告警直推 AM。false = 回滚（只落库，不推 AM）。 */
  private boolean enabled = false;

  /** Alertmanager base URL（不含路径），推送时追加 {@code /api/v2/alerts}。由部署配置注入。 */
  private String endpoint = "";

  /** 单次推送 HTTP 读超时（毫秒）。短超时避免拖慢 emit 主路径。 */
  private long timeoutMillis = 2000L;

  /** 连接超时（毫秒）。 */
  private long connectTimeoutMillis = 2000L;

  /** OPEN 告警周期重发间隔（秒），维持 AM firing 状态；须 &lt; AM resolve_timeout(默认 5m)。 */
  private int resendIntervalSeconds = 60;

  /** 单轮重发最多取多少条 OPEN 告警。 */
  private int reemitBatchSize = 200;

  /** 异步推送线程池上限（off 关键路径，1-2 足够）。 */
  private int emitThreads = 2;
}
