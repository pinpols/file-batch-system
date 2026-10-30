package io.github.pinpols.batch.common.plugin;

/**
 * 插件声明的重复副作用安全能力。UNKNOWN 必须失败关闭，调用方不得将未声明能力推断为安全。
 *
 * <p>Import LOAD 使用该能力校验 chunk checkpoint 的跨库恢复安全；Export 使用它校验
 * {@link ExportDataPlugin#onRegistered} 在任务重领时是否可重复调用；PROCESS 使用它判断插件生命周期能否在租约回收后安全重放。
 */
public enum IdempotencyCapability {
  /** 业务表多租 UNIQUE 约束 + {@code INSERT ... ON CONFLICT DO NOTHING/UPDATE},DB 层幂等约束防重复写。 */
  IDEMPOTENT_BY_UNIQUE_CONSTRAINT,

  /** 插件实现按稳定业务身份自行去重/合并，不依赖数据库唯一约束。 */
  IDEMPOTENT_BY_PLUGIN_LOGIC,

  /** 明确不幂等(直接 INSERT 无 ON CONFLICT)——由对应 Worker 在恢复/续跑前拒绝或设置 UNKNOWN 围栏。 */
  NONE,

  /** 默认值。plugin 未声明能力 = 不能假设安全;由对应 Worker 的恢复策略失败关闭。 */
  UNKNOWN
}
