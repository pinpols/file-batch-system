package com.example.batch.worker.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ADR-038 平台 worker 续跑位点配置。
 *
 * <p>默认 {@code enabled=false} — 灰度开关:无开关时 LoadStep / GenerateStep 行为与今天完全一致(从 0 跑、不写位点)。
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

  /** ADR-038 续跑位点总开关。默认 false,行为与未引入本功能时一致。 */
  private boolean enabled = false;
}
