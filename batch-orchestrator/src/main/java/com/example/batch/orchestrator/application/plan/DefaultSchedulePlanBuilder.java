package com.example.batch.orchestrator.application.plan;

import com.example.batch.common.enums.ShardStrategy;
import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.domain.entity.WorkflowDefinitionRecord;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link SchedulePlan} 组装入口：从 Redis 缓存读 job_definition / workflow_definition，与 runtime params
 * 合并后派生分区数、分区键（{@code jobCode:bizDate:partitionNo}）、worker route。
 *
 * <p>分区数按 {@code shardStrategy} 决定：
 *
 * <ul>
 *   <li>{@code STATIC}：从 params 里读固定值。
 *   <li>{@code DYNAMIC / AUTO}：走 {@link #resolveDynamicPartitionCount}——按 {@code @Order} 串起的
 *       {@link PartitionCountResolver} 策略链，第一个返回正值的结果胜出。
 *   <li>{@code NONE}：固定为 1。
 * </ul>
 *
 * <p>结果统一经 {@link #normalizePartitionCount} 夹到 {@code [min, max]} 区间，{@code maxPartitionCount}
 * 硬上限为 256 防止失控膨胀。
 */
@Component
@RequiredArgsConstructor
public class DefaultSchedulePlanBuilder implements SchedulePlanBuilder {

  private final OrchestratorConfigCacheService configCacheService;

  /** 有序的解析器链；第一个返回正值的结果胜出。由 Spring 通过 @Order 注入。 */
  private final List<PartitionCountResolver> dynamicResolvers;

  @Override
  public SchedulePlan build(SchedulePlanCommand command) {
    JobDefinitionRecord jobDefinition =
        configCacheService.findEnabledJobDefinition(command.tenantId(), command.jobCode());
    WorkflowDefinitionRecord workflowDefinition =
        configCacheService.findEnabledWorkflowDefinition(command.tenantId(), command.jobCode());
    Map<String, Object> planParams = mergePlanParams(jobDefinition, command.params());

    SchedulePlan plan = new SchedulePlan();
    plan.setTenantId(command.tenantId());
    plan.setJobCode(command.jobCode());
    plan.setBizDate(command.bizDate());
    plan.setJobDefinitionId(jobDefinition == null ? null : jobDefinition.id());
    plan.setWorkflowDefinitionId(workflowDefinition == null ? null : workflowDefinition.id());
    plan.setQueueCode(jobDefinition == null ? null : jobDefinition.queueCode());
    plan.setWorkerGroup(jobDefinition == null ? null : jobDefinition.workerGroup());
    plan.setWindowCode(jobDefinition == null ? null : jobDefinition.windowCode());
    plan.setDefaultWorkerType(jobDefinition == null ? null : jobDefinition.jobType());
    plan.setPriority(jobDefinition == null ? 5 : jobDefinition.priority());
    plan.setPartitionCount(resolvePartitionCount(jobDefinition, planParams));

    WorkerRouteModel route = new WorkerRouteModel();
    route.setWorkerType(plan.getDefaultWorkerType());
    route.setPriority(plan.getPriority());
    route.setAvailable(true);
    plan.setDefaultWorkerRoute(route);

    List<SchedulePlan.PartitionPlan> partitionPlans = new ArrayList<>();
    int partitionCount =
        plan.getPartitionCount() == null || plan.getPartitionCount() <= 0
            ? 1
            : plan.getPartitionCount();
    for (int partitionNo = 1; partitionNo <= partitionCount; partitionNo++) {
      SchedulePlan.PartitionPlan partitionPlan = new SchedulePlan.PartitionPlan();
      partitionPlan.setPartitionNo(partitionNo);
      partitionPlan.setPartitionKey(
          command.jobCode() + ":" + command.bizDate() + ":" + partitionNo);
      partitionPlan.setBusinessKey(command.jobCode() + ":" + command.bizDate());
      partitionPlan.setWorkerRoute(route);
      partitionPlans.add(partitionPlan);
    }
    plan.setPartitions(partitionPlans);
    return plan;
  }

  private Map<String, Object> mergePlanParams(
      JobDefinitionRecord jobDefinition, Map<String, Object> runtimeParams) {
    Map<String, Object> merged = new LinkedHashMap<>();
    if (jobDefinition != null && jobDefinition.defaultParams() != null) {
      merged.putAll(jobDefinition.defaultParams());
    }
    if (runtimeParams != null) {
      merged.putAll(runtimeParams);
    }
    return merged;
  }

  private int resolvePartitionCount(JobDefinitionRecord jobDefinition, Map<String, Object> params) {
    ShardStrategy shardStrategy =
        jobDefinition == null
            ? ShardStrategy.NONE
            : ShardStrategy.fromCode(jobDefinition.shardStrategy());
    int minPartitionCount =
        Math.max(1, firstPositiveInt(params.get("minPartitionCount"), params.get("minShardCount")));
    int maxPartitionCount =
        clampMaxPartitionCount(
            firstPositiveInt(
                params.get("maxPartitionLimit"),
                params.get("maxPartitionCount"),
                params.get("maxShardCount"),
                params.get("shardMaxCount")));
    int partitionCount =
        switch (shardStrategy) {
          case STATIC ->
              firstPositiveInt(
                  params.get("partitionCount"),
                  params.get("staticPartitionCount"),
                  params.get("shardCount"),
                  params.get("fixedShardCount"));
          case DYNAMIC, AUTO -> resolveDynamicPartitionCount(jobDefinition, params, shardStrategy);
          case NONE -> 1;
        };
    return normalizePartitionCount(partitionCount, minPartitionCount, maxPartitionCount);
  }

  /**
   * 串联所有注入的 {@link PartitionCountResolver} 策略（按 {@code @Order} 排序）。
   *
   * <p>解析步骤（第一个返回正值的结果胜出）：
   *
   * <ol>
   *   <li>{@link ExplicitPartitionCountResolver} — 调用方显式指定的覆盖值
   *   <li>{@link SizeBasedPartitionCountResolver} — 数据量（条数/字节） ÷ 每分区目标量
   *   <li>{@link RuntimeBasedPartitionCountResolver} — 历史执行时长 ÷ 目标时长
   *   <li>{@link WorkerBasedPartitionCountResolver} — 在线 Worker 数 × 分区因子
   * </ol>
   *
   * 当所有解析器均返回 {@code 0} 时，回退为 {@code 1}。
   */
  private int resolveDynamicPartitionCount(
      JobDefinitionRecord jobDefinition, Map<String, Object> params, ShardStrategy shardStrategy) {
    for (PartitionCountResolver resolver : dynamicResolvers) {
      int count = resolver.resolve(jobDefinition, params, shardStrategy);
      if (count > 0) {
        return count;
      }
    }
    return 1;
  }

  private int normalizePartitionCount(
      int partitionCount, int minPartitionCount, int maxPartitionCount) {
    int normalizedMax = Math.max(minPartitionCount, maxPartitionCount);
    int normalized = partitionCount <= 0 ? minPartitionCount : partitionCount;
    return Math.min(Math.max(normalized, minPartitionCount), normalizedMax);
  }

  private int clampMaxPartitionCount(int partitionCount) {
    return partitionCount <= 0 ? 256 : Math.min(partitionCount, 256);
  }

  private int firstPositiveInt(Object... values) {
    for (Object value : values) {
      if (value instanceof Number number && number.intValue() > 0) {
        return number.intValue();
      }
      if (value != null) {
        try {
          int parsed = Integer.parseInt(String.valueOf(value).trim());
          if (parsed > 0) {
            return parsed;
          }
        } catch (NumberFormatException ignored) {
        }
      }
    }
    return 0;
  }
}
