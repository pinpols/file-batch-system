package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 分片"数量决策"策略 — 控制 orchestrator 给 job_instance 派多少 partition。
 *
 * <p><b>注意命名易误解:本枚举不决定"按什么字段切"</b>。具体切分维度(按文件行 / 按字节 range / 按业务键 / 按机构号 等)由 worker 端的 step /
 * plugin 实现自己解释 {@code job_partition.partition_no} + {@code partition_key} + {@code input_snapshot}
 * 决定 — 平台只发"你是第 N/M 号 partition,自己看着干哪部分"。
 *
 * <p><b>四个取值对 partition 数量的影响:</b>
 *
 * <ul>
 *   <li>{@link #NONE} — 固定 1 个 partition,串行执行
 *   <li>{@link #STATIC} — 从 params 读固定值(优先级:partitionCount / staticPartitionCount / shardCount /
 *       fixedShardCount)
 *   <li>{@link #DYNAMIC} — 走 {@code DefaultSchedulePlanBuilder} 的 4-resolver 链(Explicit > Size >
 *       Runtime > Worker),其中 worker-based resolver 用 {@code 在线 worker 数 × 2} 作为并发因子
 *   <li>{@link #AUTO} — 同 DYNAMIC,worker-based resolver 因子为 1(更保守)
 * </ul>
 *
 * <p><b>不影响</b>切分语义:DYNAMIC 不会"自动按某字段切",它只是"动态算数量";想按机构号切要 worker plugin 读 partition_key 自己路由。详见
 * {@code docs/architecture/core-model.md} §3.6 + {@code
 * docs/architecture/adr/ADR-005-partition-count-resolver-chain.md}。
 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum ShardStrategy implements DictEnum {
  NONE("NONE", "不分片"),
  STATIC("STATIC", "静态分片"),
  DYNAMIC("DYNAMIC", "动态分片"),
  AUTO("AUTO", "自动分片");

  private final String code;
  private final String label;

  /** 空白或未知 code 回落到 NONE。 */
  public static ShardStrategy fromCode(String code) {
    ShardStrategy match = DictEnum.fromCode(ShardStrategy.class, code);
    return match != null ? match : NONE;
  }
}
