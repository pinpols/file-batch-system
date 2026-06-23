package io.github.pinpols.batch.common.plugin;

/**
 * ADR-038 R3-3:Import LOAD plugin 在续跑场景下的数据安全契约。
 *
 * <p>跨库无 1PC(plugin 写业务库 / 续跑位点写平台库),崩溃窗口重派会让 LoadStep 重做最后一个未 advance 的 chunk。 因此「续跑安全」== 「plugin
 * 对同一行重复 loadChunk 不会双写或脏写」。{@link ImportLoadPlugin#idempotencyCapability()} 是 plugin
 * 自报的能力,LoadStep 在 {@code batch.worker.checkpoint.enabled=true} 进入续跑路径前据此前置校验,{@link #NONE} /
 * {@link #UNKNOWN} 直接拒跑。
 *
 * <p>详见 {@code docs/runbook/platform-worker-checkpoint-howto.md} §前置校验。
 */
public enum IdempotencyCapability {
  /** 业务表多租 UNIQUE 约束 + {@code INSERT ... ON CONFLICT DO NOTHING/UPDATE},DB 层幂等约束防重复写。 */
  IDEMPOTENT_BY_UNIQUE_CONSTRAINT,

  /** plugin 自身在 loadChunk 内做去重(查后写 / 业务键判断),不依赖 DB 唯一约束。 */
  IDEMPOTENT_BY_PLUGIN_LOGIC,

  /** 明确不幂等(直接 INSERT 无 ON CONFLICT)— 续跑会双写,LoadStep 必须拒跑。 */
  NONE,

  /** 默认值。plugin 未声明能力 = 不能假设安全;LoadStep 在续跑开关开时拒跑。 */
  UNKNOWN
}
