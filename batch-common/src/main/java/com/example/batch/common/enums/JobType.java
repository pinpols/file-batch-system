package com.example.batch.common.enums;

import java.util.Set;
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
  WORKFLOW("WORKFLOW", "工作流任务"),
  /** ADR-029:原子任务(Task SPI:shell/sql/stored-proc/http),由专用 batch-worker-atomic 执行。 */
  ATOMIC("ATOMIC", "原子任务"),
  /**
   * ADR-046:文件束聚合(用户单次提交多文件→多表)。launch 按提交 manifest 展成一个 job_instance 下 K 个异构 partition(各绑
   * source_file_id/template_code/target_ref),把控制面 churn 从 O(N) 降到 O(N/K)。归 {@link BatchType#IMPORT}
   * 桶。本枚举仅登记类型;派发与展开逻辑见 orchestrator 的束作业处理链。
   */
  BUNDLE_IMPORT("BUNDLE_IMPORT", "文件束聚合任务"),
  /**
   * ADR-046 Phase3:文件束导出(用户单次导出多表→多文件)。每 partition 绑一个导出 {@code template_code}(源表/查询), 归 {@link
   * BatchType#EXPORT} 桶。绑定经 {@code job_partition.template_code}(导出无源文件,{@code source_file_id} 为空)。
   */
  BUNDLE_EXPORT("BUNDLE_EXPORT", "文件束导出任务"),
  /**
   * ADR-046 Phase3:文件束分发(用户单次把多文件分发到多下游)。每 partition 绑一个 {@code source_file_id}(待分发文件)+ {@code
   * target_ref}(下游渠道 channel_code),归 {@link BatchType#DISPATCH} 桶。分发无导出模板({@code template_code}
   * 为空)。
   */
  BUNDLE_DISPATCH("BUNDLE_DISPATCH", "文件束分发任务");

  private final String code;
  private final String label;

  /** ADR-046 文件束作业类型集合(IMPORT/EXPORT/DISPATCH);束语义共享同一展开骨架,绑定 profile 各异。 */
  private static final Set<JobType> BUNDLE_TYPES =
      Set.of(BUNDLE_IMPORT, BUNDLE_EXPORT, BUNDLE_DISPATCH);

  /** 是否文件束作业(展成 K 个异构 partition 的束骨架)。 */
  public boolean isBundle() {
    return BUNDLE_TYPES.contains(this);
  }

  /** 按 job_type code(DB 字面量)判定是否文件束作业;未知 / null → false。 */
  public static boolean isBundleCode(String code) {
    if (code == null) {
      return false;
    }
    for (JobType type : BUNDLE_TYPES) {
      if (type.code.equals(code)) {
        return true;
      }
    }
    return false;
  }

  /** 投影到公共业务类型字典 {@link BatchType}。每个枚举值必须有非空映射。 */
  public BatchType batchType() {
    return switch (this) {
      case GENERAL -> BatchType.GENERAL;
      case IMPORT -> BatchType.IMPORT;
      case EXPORT -> BatchType.EXPORT;
      case PROCESS -> BatchType.PROCESS;
      case DISPATCH -> BatchType.DISPATCH;
      case WORKFLOW -> BatchType.WORKFLOW;
      // ATOMIC(原子任务)无独立 BatchType,归 GENERAL carryover 桶(BatchType 仅作指标/分类投影)。
      case ATOMIC -> BatchType.GENERAL;
      // BUNDLE_IMPORT(文件束):一束多文件导入,投影到 IMPORT 桶。
      case BUNDLE_IMPORT -> BatchType.IMPORT;
      // BUNDLE_EXPORT(文件束导出):一束多表导出,投影到 EXPORT 桶。
      case BUNDLE_EXPORT -> BatchType.EXPORT;
      // BUNDLE_DISPATCH(文件束分发):一束多文件分发,投影到 DISPATCH 桶。
      case BUNDLE_DISPATCH -> BatchType.DISPATCH;
    };
  }
}
