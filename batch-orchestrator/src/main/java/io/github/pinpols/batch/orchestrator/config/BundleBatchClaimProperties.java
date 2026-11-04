package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ADR-046 批量 claim/report 端点的请求大小配置。
 *
 * <p>批量端点始终可用，只用 {@code maxBatchSize} 限制单次请求大小；是否走批量由 Worker 侧 {@code
 * batch.worker.batch-claim.enabled} 决定。原服务端 {@code enabled/jobOverrides} 从未参与准入，已于 DOC-BATCH-FLAG-1 退役；
 * 部署中残留的这两个键会被忽略，不影响启动。
 */
@Data
@ConfigurationProperties(prefix = "batch.task.batch-claim")
public class BundleBatchClaimProperties {

  /**
   * 单次批量领/报的最大 partition 数(O(N)→O(N/K) 的 K 上限)。 防止单批过大放大事务 / 超过单次 lease 续租窗口;worker 攒批与
   * orchestrator 批处理都不得超过它。
   */
  private int maxBatchSize = 50;

  /** 规整后的批大小至少为 1；配置不大于 0 时返回 1，不会关闭端点。 */
  public int effectiveBatchSize() {
    return Math.max(1, maxBatchSize);
  }
}
