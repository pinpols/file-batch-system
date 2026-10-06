package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@link StaleCompensationCommandReconciler} 对账参数（{@code batch.compensation.stale-running-reconciler}）。
 *
 * <p>注：{@code initial-delay-millis} / {@code fixed-delay-millis} 由 {@code @Scheduled} 的 SpEL 直接读取，
 * 无法走 Properties 注入，仅超时与批大小收敛到这里。
 */
@Data
@ConfigurationProperties(prefix = "batch.compensation.stale-running-reconciler")
public class StaleCompensationReconcilerProperties {

  /** RUNNING 命令被视为遗留的超时秒数；{@code <= 0} 表示禁用对账。默认 3600。 */
  private long timeoutSeconds = 3_600L;

  /** 单批标记失败的最大记录数；{@code <= 0} 表示禁用对账。默认 100。 */
  private int batchSize = 100;
}
