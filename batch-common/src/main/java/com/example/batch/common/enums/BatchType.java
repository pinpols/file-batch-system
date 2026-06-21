package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 批量业务类型(数据从哪来 / 到哪去)的统一字典,用于消除 {@link JobType}(console-api 用)与 {@link PipelineType}(worker-core
 * 用)双份并存的暗债。设计依据:{@code docs/design/batch-classification-and-gaps.md} §1.1 / §3.2 / §4 P1-1。
 *
 * <ul>
 *   <li>{@link #IMPORT} —— 外部 → 系统(文件 / MQ / 拉取 API),如 CSV → DB
 *   <li>{@link #EXPORT} —— 系统 → 外部,如 DB → 下游 API
 *   <li>{@link #PROCESS} —— 系统内部加工(聚合 / 清洗 / 状态推进),首期占位,worker 模块按需补
 *   <li>{@link #DISPATCH} —— 结果向外分发(MQ / FTP / 第三方接口)
 *   <li>{@link #SYNC} —— 系统之间对齐(DB→DB / CDC),首期占位,worker 模块按需补
 *   <li>{@link #GENERAL} —— carryover:未归类 / 无 pipeline 模板的回退
 *   <li>{@link #WORKFLOW} —— carryover:工作流编排型,本身不是数据流类型
 * </ul>
 *
 * <p>不直接落 DB:DB 仍写 {@link JobType#code()} / {@link PipelineType#code()};本枚举仅作为公共投影, 让"加 PROCESS /
 * SYNC 改一处"成为可能。具体投影见 {@link JobType#batchType()} / {@link PipelineType#batchType()}。
 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum BatchType implements DictEnum {
  IMPORT("IMPORT", "导入"),
  EXPORT("EXPORT", "导出"),
  PROCESS("PROCESS", "加工"),
  DISPATCH("DISPATCH", "分发"),
  SYNC("SYNC", "同步"),
  GENERAL("GENERAL", "通用"),
  WORKFLOW("WORKFLOW", "工作流");

  private final String code;
  private final String label;
}
