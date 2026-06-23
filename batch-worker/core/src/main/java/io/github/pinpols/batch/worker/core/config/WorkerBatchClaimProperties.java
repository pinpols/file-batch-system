package io.github.pinpols.batch.worker.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ADR-046 Phase 2 切片 2.3 —— worker 消费端攒批总开关(默认关)。
 *
 * <p>flag 关(默认):worker 走现有「1 record = 1 task = 1 claim + 1 report」per-record 路径,**零影响**。 flag
 * 开:消费端攒 K 条 → 一次 {@code claim-batch} → 逐 partition 独立执行并沿用逐任务 report。当前阶段先降低 CLAIM 往返,report-batch
 * 仍保留在 orchestrator 端点侧,worker 未接入。
 *
 * <p>护栏(见 {@code docs/backlog/adr046-phase2-2.3-worker-batch-construction.md}):partition 执行/幂等/
 * lease/DLQ/背压逐项保持;无束状态机、无 worker 束循环;**生产启用前须全栈压测**。{@code maxBatchSize} 必须 ≤ orchestrator 侧
 * {@code batch.task.batch-claim.maxBatchSize}(否则批调用被 4xx 拒)。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.batch-claim")
public class WorkerBatchClaimProperties {

  /** 总开关;false(默认)= 全走 per-record 单条路径。 */
  private boolean enabled = false;

  /** 单次 claim-batch 的最大 partition 数(HTTP chunk 大小)。 */
  private int maxBatchSize = 32;

  /** 规整批大小:至少 1(配置 ≤0 视为关批量,退化逐条)。 */
  public int effectiveBatchSize() {
    return Math.max(1, maxBatchSize);
  }
}
