package io.github.pinpols.batch.worker.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ADR-038 平台 worker 续跑位点配置。
 *
 * <p>默认 {@code enabled=true}(P0 生产化,2026-07)—— 系统未上线故无需影子期/租户渐进灰度,验证走 sim/e2e 全链; 开关保留作**回滚手段**:显式设
 * {@code batch.worker.checkpoint.enabled=false} + worker 重启即完全退回未引入本功能的行为 (从 0 跑、不写位点)。
 *
 * <p>开关打开后,LOAD / GENERATE 阶段会:
 *
 * <ol>
 *   <li>启动时 {@code ProcessingPositionStore.load()} 取上次位点;completed=true 则幂等跳过
 *   <li>从上次位点续跑(Import skip 行;Export 续 cursor)
 *   <li>每 chunk / page 完成时 {@code positionStore.advance()} 推进位点
 *   <li>阶段整体完成时 {@code positionStore.markCompleted()} 标记
 * </ol>
 *
 * <p>同事务约束:Import 业务写在租户 business DB、位点在平台 DB,跨库无法 1PC 原子。 实施采用「业务先 commit → 位点后 advance」+ 插件幂等(多租
 * UNIQUE + ON CONFLICT)回退; 崩溃窗口内最多重复处理 1 个 chunk,数据安全交由 plugin 幂等约束守护。详见 {@code
 * docs/runbook/platform-worker-checkpoint-howto.md}。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.checkpoint")
public class WorkerCheckpointProperties {

  /** ADR-038 续跑位点总开关。默认 true(P0 生产化);显式设 false 即回滚到未引入本功能时的行为。 */
  private boolean enabled = true;

  /** P1 阶段级续跑(ADR-038 §决策四)子开关。 */
  private final StageSkip stageSkip = new StageSkip();

  /**
   * 阶段级续跑(P1):多 stage pipeline 崩溃重派时,跳过上一 attempt 已成功且副作用可从持久状态重建的 stage, 而非从首 stage 全量重来。
   *
   * <p><b>本 PR 默认 {@code enabled=false}</b>——只交付能力 + 开关关,验证充分后另 PR 议翻 true, 降低合入风险。关开关即完全退回「从首
   * stage 全量重跑」的原行为(回归保护)。
   *
   * <p>安全边界:该开关只对**跳过安全**的 stage 生效(见 {@code
   * AbstractStageExecutor#skipSafeStages()});其副作用必须已持久化到可由稳定键重建的位置 (如 PROCESS 的 {@code
   * process_staging} 按 {@code batch-<taskId>} 键)。 靠内存 attribute 传递中间产物(import/export/dispatch 的
   * file path 等)的 stage 不在跳过范围, 跳过会丢失下游输入。
   */
  @Data
  public static class StageSkip {

    /** 阶段级续跑总开关。默认 false(本 PR 只交付能力,不默认启用)。 */
    private boolean enabled = false;
  }
}
