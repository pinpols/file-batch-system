package io.github.pinpols.batch.orchestrator.application.service.dryrun;

/**
 * ADR-026 演练计划服务。按 {@link DryRunLevel} 路由到 L1/L2/L3：
 *
 * <ul>
 *   <li>L1 CONFIG_VALIDATE — job_definition / workflow_definition 校验：cron / DAG 可解 / 参数齐 /
 *       fileTemplate 字段映射对得上
 *   <li>L2 SCHEDULE_PLAN — 给定 bizDate，复用 SchedulePlanBuilder 输出预计 partition / worker route，但不写
 *       instance
 *   <li>L3 EXECUTION_PLAN — 在 L2 基础上叠加 SQL explain / 文件路径解析 / MinIO key 预生成 / 渠道 reachability check
 * </ul>
 *
 * <p>边界：禁止写业务表 / 调外部投递；禁止真实 SQL 执行（仅 EXPLAIN）。FULL_SIMULATION 不做。
 */
public interface DryRunPlanService {

  DryRunPlanResult plan(DryRunPlanRequest request);
}
