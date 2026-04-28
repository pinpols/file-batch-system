package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * DAG 工作流节点的运行状态。
 *
 * <p>注意:节点级别生命周期与 job_instance 不完全对齐 —— 节点没有 CREATED/WAITING(从依赖 ready 即进 READY)、 也没有
 * CANCELLED/TERMINATED(取消/终止是 workflow_run 级别概念,不在节点级别);但节点有 SKIPPED (条件分支不命中或父失败传播跳过)是 DAG
 * 特有的合法状态。
 *
 * <p>S4.1:加 {@link #lifecycle()} 投影,让节点状态映射到 {@link BatchLifecycleStatus} 公共生命周期, 与其它 6 个 status
 * 枚举(JobStatus / JobInstanceStatus / PartitionStatus / TaskStatus / StepInstanceStatus /
 * WorkflowRunStatus)对齐;workflow_run 汇总节点状态时可以走统一 terminal / 失败判定。SKIPPED 投影为
 * SUCCESS(节点跳过不算失败,workflow 层视为成功通过)。
 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum WorkflowNodeRunStatus implements DictEnum {
  READY("READY", "待执行"),
  RUNNING("RUNNING", "执行中"),
  SUCCESS("SUCCESS", "成功"),
  FAILED("FAILED", "失败"),
  SKIPPED("SKIPPED", "跳过");

  private final String code;
  private final String label;

  /** 投影到公共 {@link BatchLifecycleStatus};SKIPPED 视为 SUCCESS(节点跳过不算失败)。 */
  public BatchLifecycleStatus lifecycle() {
    return switch (this) {
      case READY -> BatchLifecycleStatus.READY;
      case RUNNING -> BatchLifecycleStatus.RUNNING;
      case SUCCESS, SKIPPED -> BatchLifecycleStatus.SUCCESS;
      case FAILED -> BatchLifecycleStatus.FAILED;
    };
  }
}
