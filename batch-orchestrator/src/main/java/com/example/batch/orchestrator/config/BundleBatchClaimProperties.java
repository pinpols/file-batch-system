package com.example.batch.orchestrator.config;

import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ADR-046 Phase 2 —— 多行 claim/report 批量变体的总开关(切片 2.0)。
 *
 * <p>当前仅承载开关与批大小,**尚无任何热路径消费**:批量 claim/report 变体在切片 2.1/2.2 落地后才读取本配置决定是否走批量路径。默认 {@code
 * enabled=false},单 partition 路径不受影响。
 *
 * <p>设计护栏(见 {@code docs/backlog/adr046-phase2-plan-2026-06-23.md}):纯加法、默认关、 先只对 {@code BUNDLE_*} 作业
 * opt-in;批量只省控制面往返(O(N)→O(N/K)),不引入束状态机 / worker 束循环,partition 语义/幂等/lease 全不变。
 */
@Data
@ConfigurationProperties(prefix = "batch.task.batch-claim")
public class BundleBatchClaimProperties {

  /** 全局总开关;false(默认)时所有作业走现有单 partition claim/report。 */
  private boolean enabled = false;

  /**
   * 单次批量领/报的最大 partition 数(O(N)→O(N/K) 的 K 上限)。 防止单批过大撑爆事务 / 超过单次 lease 续租窗口;worker 攒批与
   * orchestrator 批处理都不得超过它。
   */
  private int maxBatchSize = 50;

  /**
   * 按作业编码(jobCode)覆盖全局开关:key=jobCode,value=是否启用批量。 命中则以覆盖值为准,未命中回退 {@link #enabled}。用于「先只对某些
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

  /** 规整后的批大小:至少 1(配置 ≤0 视为关批量,退化为单条)。 */
  public int effectiveBatchSize() {
    return Math.max(1, maxBatchSize);
  }
}
