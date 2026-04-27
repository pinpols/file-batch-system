package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 调度配置层(console / orchestrator)使用的"任务类型"枚举,落 DB 字段 {@code job_definition.job_type}。
 *
 * <p>历史上与 {@link PipelineType}(worker 视角)分别演化形成双份枚举(详见 {@code
 * docs/design/batch-classification-and-gaps.md} §3.2)。统一公共投影见 {@link BatchType};通过 {@link
 * #batchType()} 反查后,业务侧"按业务类型派发"的逻辑只依赖 BatchType,加 PROCESS / SYNC 时无需再改这里。
 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum JobType implements DictEnum {
  GENERAL("GENERAL", "通用任务"),
  IMPORT("IMPORT", "导入任务"),
  EXPORT("EXPORT", "导出任务"),
  PROCESS("PROCESS", "加工任务"),
  DISPATCH("DISPATCH", "分发任务"),
  WORKFLOW("WORKFLOW", "工作流任务");

  private final String code;
  private final String label;

  /** 投影到公共业务类型字典 {@link BatchType}。每个枚举值必须有非空映射。 */
  public BatchType batchType() {
    return switch (this) {
      case GENERAL -> BatchType.GENERAL;
      case IMPORT -> BatchType.IMPORT;
      case EXPORT -> BatchType.EXPORT;
      case PROCESS -> BatchType.PROCESS;
      case DISPATCH -> BatchType.DISPATCH;
      case WORKFLOW -> BatchType.WORKFLOW;
    };
  }
}
