package com.example.batch.orchestrator.application.plan;

import com.example.batch.common.enums.ShardStrategy;
import com.example.batch.common.enums.WorkerRegistryStatus;
import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.domain.entity.WorkflowDefinitionRecord;
import com.example.batch.orchestrator.repository.JobDefinitionRepository;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
import com.example.batch.orchestrator.repository.WorkflowDefinitionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class DefaultSchedulePlanBuilder implements SchedulePlanBuilder {

    private final JobDefinitionRepository jobDefinitionRepository;
    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final WorkerRegistryRepository workerRegistryRepository;

    @Override
    public SchedulePlan build(SchedulePlanCommand command) {
        JobDefinitionRecord jobDefinition = jobDefinitionRepository.findFirstByTenantIdAndJobCodeAndEnabled(command.tenantId(), command.jobCode(), true);
        WorkflowDefinitionRecord workflowDefinition = workflowDefinitionRepository.findFirstByTenantIdAndWorkflowCodeAndEnabled(command.tenantId(), command.jobCode(), true);
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
        int partitionCount = plan.getPartitionCount() == null || plan.getPartitionCount() <= 0 ? 1 : plan.getPartitionCount();
        for (int partitionNo = 1; partitionNo <= partitionCount; partitionNo++) {
            SchedulePlan.PartitionPlan partitionPlan = new SchedulePlan.PartitionPlan();
            partitionPlan.setPartitionNo(partitionNo);
            partitionPlan.setPartitionKey(command.jobCode() + ":" + command.bizDate() + ":" + partitionNo);
            partitionPlan.setBusinessKey(command.jobCode() + ":" + command.bizDate());
            partitionPlan.setWorkerRoute(route);
            partitionPlans.add(partitionPlan);
        }
        plan.setPartitions(partitionPlans);
        return plan;
    }

    private Map<String, Object> mergePlanParams(JobDefinitionRecord jobDefinition, Map<String, Object> runtimeParams) {
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
        ShardStrategy shardStrategy = jobDefinition == null
                ? ShardStrategy.NONE
                : ShardStrategy.fromCode(jobDefinition.shardStrategy());
        int minPartitionCount = Math.max(1, firstPositiveInt(
                params.get("minPartitionCount"),
                params.get("minShardCount")
        ));
        int maxPartitionCount = clampMaxPartitionCount(firstPositiveInt(
                params.get("maxPartitionLimit"),
                params.get("maxPartitionCount"),
                params.get("maxShardCount"),
                params.get("shardMaxCount")
        ));
        int partitionCount = switch (shardStrategy) {
            case STATIC -> firstPositiveInt(
                    params.get("partitionCount"),
                    params.get("staticPartitionCount"),
                    params.get("shardCount"),
                    params.get("fixedShardCount")
            );
            case DYNAMIC, AUTO -> resolveDynamicPartitionCount(jobDefinition, params, shardStrategy);
            case NONE -> 1;
        };
        return normalizePartitionCount(partitionCount, minPartitionCount, maxPartitionCount);
    }

    /**
     * 动态分片优先按工作量估算，再用在线 worker 容量兜底，避免固定 1 分片或固定写死分片数。
     */
    private int resolveDynamicPartitionCount(JobDefinitionRecord jobDefinition,
                                             Map<String, Object> params,
                                             ShardStrategy shardStrategy) {
        int explicitPartitionCount = firstPositiveInt(
                params.get("partitionCount"),
                params.get("estimatedPartitionCount"),
                params.get("suggestedPartitionCount"),
                params.get("shardCount")
        );
        if (explicitPartitionCount > 0) {
            return explicitPartitionCount;
        }
        int sizeBasedPartitionCount = resolveSizeBasedPartitionCount(params);
        int runtimeBasedPartitionCount = resolveRuntimeBasedPartitionCount(params);
        int workloadBasedPartitionCount = Math.max(sizeBasedPartitionCount, runtimeBasedPartitionCount);
        int workerBasedPartitionCount = resolveWorkerBasedPartitionCount(jobDefinition, params, shardStrategy);

        if (workloadBasedPartitionCount > 0 && workerBasedPartitionCount > 0) {
            return Math.min(workloadBasedPartitionCount, workerBasedPartitionCount);
        }
        if (workloadBasedPartitionCount > 0) {
            return workloadBasedPartitionCount;
        }
        if (workerBasedPartitionCount > 0) {
            return workerBasedPartitionCount;
        }
        return 1;
    }

    private int resolveSizeBasedPartitionCount(Map<String, Object> params) {
        long estimatedItems = firstPositiveLong(
                params.get("estimatedItemCount"),
                params.get("recordCount"),
                params.get("itemCount"),
                params.get("totalCount")
        );
        int targetItemsPerPartition = firstPositiveInt(
                params.get("targetItemsPerPartition"),
                params.get("targetShardSize"),
                params.get("itemsPerPartition")
        );
        if (estimatedItems > 0 && targetItemsPerPartition > 0) {
            return ceilDiv(estimatedItems, targetItemsPerPartition);
        }
        long estimatedBytes = firstPositiveLong(
                params.get("estimatedFileSizeBytes"),
                params.get("fileSizeBytes"),
                params.get("sourceFileSizeBytes")
        );
        long targetBytesPerPartition = firstPositiveLong(
                params.get("targetBytesPerPartition"),
                params.get("targetShardBytes")
        );
        if (estimatedBytes > 0 && targetBytesPerPartition > 0) {
            return ceilDiv(estimatedBytes, targetBytesPerPartition);
        }
        return 0;
    }

    private int resolveRuntimeBasedPartitionCount(Map<String, Object> params) {
        long historicalDurationSeconds = firstPositiveLong(
                params.get("historicalAverageDurationSeconds"),
                params.get("historicalDurationSeconds"),
                params.get("expectedDurationSeconds")
        );
        int targetDurationSeconds = firstPositiveInt(
                params.get("targetPartitionDurationSeconds"),
                params.get("targetDurationSeconds")
        );
        if (historicalDurationSeconds > 0 && targetDurationSeconds > 0) {
            return ceilDiv(historicalDurationSeconds, targetDurationSeconds);
        }
        return 0;
    }

    private int resolveWorkerBasedPartitionCount(JobDefinitionRecord jobDefinition,
                                                 Map<String, Object> params,
                                                 ShardStrategy shardStrategy) {
        long onlineWorkerCount = resolveOnlineWorkerCount(jobDefinition, params);
        if (onlineWorkerCount <= 0) {
            return 0;
        }
        int partitionFactor = firstPositiveInt(
                params.get("partitionFactor"),
                params.get("workerPartitionFactor"),
                shardStrategy == ShardStrategy.DYNAMIC ? 2 : 1
        );
        return (int) Math.min(256L, onlineWorkerCount * partitionFactor);
    }

    private long resolveOnlineWorkerCount(JobDefinitionRecord jobDefinition, Map<String, Object> params) {
        if (jobDefinition == null || !StringUtils.hasText(jobDefinition.tenantId())) {
            return firstPositiveLong(params.get("onlineWorkerCount"), params.get("workerCount"));
        }
        if (StringUtils.hasText(jobDefinition.workerGroup())) {
            return workerRegistryRepository.countByTenantIdAndWorkerGroupAndStatus(
                    jobDefinition.tenantId(),
                    jobDefinition.workerGroup(),
                    WorkerRegistryStatus.ONLINE.code()
            );
        }
        return workerRegistryRepository.countByTenantIdAndStatus(
                jobDefinition.tenantId(),
                WorkerRegistryStatus.ONLINE.code()
        );
    }

    private int normalizePartitionCount(int partitionCount, int minPartitionCount, int maxPartitionCount) {
        int normalizedMax = Math.max(minPartitionCount, maxPartitionCount);
        int normalized = partitionCount <= 0 ? minPartitionCount : partitionCount;
        return Math.min(Math.max(normalized, minPartitionCount), normalizedMax);
    }

    private int clampMaxPartitionCount(int partitionCount) {
        return partitionCount <= 0 ? 256 : Math.min(partitionCount, 256);
    }

    private int firstPositiveInt(Object... values) {
        for (Object value : values) {
            int candidate = toInt(value);
            if (candidate > 0) {
                return candidate;
            }
        }
        return 0;
    }

    private long firstPositiveLong(Object... values) {
        for (Object value : values) {
            long candidate = toLong(value);
            if (candidate > 0) {
                return candidate;
            }
        }
        return 0L;
    }

    private int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private int ceilDiv(long dividend, long divisor) {
        if (dividend <= 0 || divisor <= 0) {
            return 1;
        }
        return (int) ((dividend + divisor - 1) / divisor);
    }
}
