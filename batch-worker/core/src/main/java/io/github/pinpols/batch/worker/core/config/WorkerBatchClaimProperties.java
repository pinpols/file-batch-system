package io.github.pinpols.batch.worker.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ADR-046 Phase 2 切片 2.3 —— Worker 消费端批量认领开关（默认关闭）。
 *
 * <p>关闭时按 record 单条认领；开启时一次认领 K 个独立 partition，再在批内串行执行。HTTP 上报微批由
 * {@link OrchestratorTaskClientProperties#isReportBatchEnabled()} 独立控制，不等整批执行结束才上报。
 * 单次上报没有并发完成项时可只有一项，不能承诺每个 CLAIM 批次都得到 K 合一的 REPORT。
 *
 * <p>护栏(见 {@code docs/architecture/adr/ADR-046-file-bundle-aggregation.md}):partition 执行/幂等/
 * lease/DLQ/背压逐项保持;无束状态机、无 worker 束循环;**生产启用前须全栈压测**。{@code maxBatchSize} 必须 ≤ orchestrator 侧
 * {@code batch.task.batch-claim.maxBatchSize}(否则批调用被 4xx 拒)。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.batch-claim")
public class WorkerBatchClaimProperties {

  /** 认领开关；false（默认）表示使用 per-record 单条认领。 */
  private boolean enabled = false;

  /** 单次 claim-batch 的最大 partition 数(HTTP chunk 大小)。 */
  private int maxBatchSize = 32;

  /** 规整批大小至少为 1；是否启用批量 listener 仍由 enabled 决定。 */
  public int effectiveBatchSize() {
    return Math.max(1, maxBatchSize);
  }
}
