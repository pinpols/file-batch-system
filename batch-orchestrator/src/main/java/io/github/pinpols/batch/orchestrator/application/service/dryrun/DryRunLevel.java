package io.github.pinpols.batch.orchestrator.application.service.dryrun;

import io.github.pinpols.batch.common.enums.DictEnum;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * ADR-026 dry-run 三层粒度。priority-scope §ADR-026 写死 L1/L2/L3，FULL_SIMULATION 红线不做。
 *
 * <ul>
 *   <li>{@link #CONFIG_VALIDATE} — 解析 cron / 业务日 / 参数 / DAG / fileTemplate / SQL；只读 + 不调外
 *   <li>{@link #SCHEDULE_PLAN} — 给定 bizDate，输出"会触发哪些 job / 预计 instance / 分区数 / Worker 类型 / 输入输出文件"
 *   <li>{@link #EXECUTION_PLAN} — task 级：SQL explain / 文件路径解析 / 模板字段检查 / MinIO key 预生成 / 下游
 *       endpoint reachability
 * </ul>
 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum DryRunLevel implements DictEnum {
  CONFIG_VALIDATE("CONFIG_VALIDATE", "配置校验"),
  SCHEDULE_PLAN("SCHEDULE_PLAN", "调度计划"),
  EXECUTION_PLAN("EXECUTION_PLAN", "执行计划");

  private final String code;
  private final String label;
}
