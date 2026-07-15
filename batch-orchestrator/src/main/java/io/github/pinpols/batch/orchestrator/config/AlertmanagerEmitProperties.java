package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * emit 直连 Alertmanager 的配置（迁移方案 §6.2，direct cutover）。
 *
 * <p>{@code enabled} 是全局唯一开关，合入即默认 {@code true}（系统未上线，直接切通）；关掉即秒级回滚——emit 只落库不推 AM。 不设
 * tenant/alert-type allowlist（无存量流量需灰度保护，回滚粒度就是全局开关）。
 */
@Data
@ConfigurationProperties(prefix = "batch.alert.am-emit")
public class AlertmanagerEmitProperties {

  /** 是否把 emit 落库后的告警直推 AM。false = 回滚（只落库，不推 AM）。 */
  private boolean enabled = true;

  /** Alertmanager base URL（不含路径），推送时追加 {@code /api/v2/alerts}。 */
  private String endpoint = "http://localhost:9093";

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
