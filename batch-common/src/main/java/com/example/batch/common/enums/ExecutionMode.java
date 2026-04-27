package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 批量执行模式,与 {@link ShardStrategy}(分多少片)与触发原因({@link TriggerType} / RunMode)正交。
 *
 * <p>设计依据:{@code docs/design/batch-classification-and-gaps.md} §1.2 / §4.1。
 *
 * <ul>
 *   <li>{@link #FULL} —— 每次全量,框架不维护水位
 *   <li>{@link #INCREMENTAL} —— 按 watermark_field 增量,job_instance 持久化 high_water_mark_in /
 *       high_water_mark_out;下次实例的 IN = 上次成功的 OUT
 *   <li>{@link #CDC} —— 流式跟踪(binlog / Debezium / Flink CDC),明确不做(见
 *       docs/design/batch-classification-and-gaps.md §5),枚举值保留为占位避免后续破坏性变更
 * </ul>
 *
 * <p>持久化字段:{@code job_definition.execution_mode}(NOT NULL DEFAULT 'FULL', 见 V73 migration)。
 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum ExecutionMode implements DictEnum {
  /** 每次全量,典型:维度小表全量重算、初始化、定期对账。 */
  FULL("FULL", "全量"),
  /** 增量,按 watermark_field 推进;首次跑 IN=NULL 表示从头。 */
  INCREMENTAL("INCREMENTAL", "增量"),
  /** CDC / 流式占位;首期仅保留枚举值,worker 暂不实现。 */
  CDC("CDC", "流式 / CDC");

  private final String code;
  private final String label;
}
