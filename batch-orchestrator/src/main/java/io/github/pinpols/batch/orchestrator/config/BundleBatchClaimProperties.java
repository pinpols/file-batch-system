package io.github.pinpols.batch.orchestrator.config;

import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ADR-046 批量 claim/report 端点的请求大小配置与预留灰度字段。
 *
 * <p>当前端点读取 {@code maxBatchSize} 限制请求大小；{@code enabled/jobOverrides} 只有
 * {@link #isEnabledForJob(String)} 取值方法，没有热路径调用，不能用来关闭批量端点。
 * 当前实现与启用边界见 {@code docs/architecture/adr/ADR-046-file-bundle-aggregation.md}。
 */
@Data
@ConfigurationProperties(prefix = "batch.task.batch-claim")
public class BundleBatchClaimProperties {

  /** 预留全局意向值，默认 false；当前不作为批量端点的拒绝条件。 */
  private boolean enabled = false;

  /**
   * 单次批量领/报的最大 partition 数(O(N)→O(N/K) 的 K 上限)。 防止单批过大放大事务 / 超过单次 lease 续租窗口;worker 攒批与
   * orchestrator 批处理都不得超过它。
   */
  private int maxBatchSize = 50;

  /**
   * 预留的作业级意向覆盖:key=jobCode,value=是否启用批量。 命中则以覆盖值为准,未命中回退 {@link #enabled}。用于「先只对某些
   * BUNDLE_* 作业灰度」。
   */
  private Map<String, Boolean> jobOverrides = Map.of();

  /** 解析某作业是否启用批量 claim/report:per-job 覆盖优先,否则回退全局开关。 */
  public boolean isEnabledForJob(String jobCode) {
    if (jobCode == null) {
      return enabled;
    }
    return jobOverrides.getOrDefault(jobCode, enabled);
  }

  /** 规整后的批大小至少为 1；配置不大于 0 时返回 1，不会关闭端点。 */
  public int effectiveBatchSize() {
    return Math.max(1, maxBatchSize);
  }
}
