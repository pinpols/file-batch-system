package com.example.batch.orchestrator.application.plan;

import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.domain.entity.WorkflowDefinitionRecord;
import com.example.batch.orchestrator.repository.JobDefinitionRepository;
import com.example.batch.orchestrator.repository.WorkflowDefinitionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DefaultSchedulePlanBuilder implements SchedulePlanBuilder {

    private final JobDefinitionRepository jobDefinitionRepository;
    private final WorkflowDefinitionRepository workflowDefinitionRepository;

    public DefaultSchedulePlanBuilder(JobDefinitionRepository jobDefinitionRepository,
                                      WorkflowDefinitionRepository workflowDefinitionRepository) {
        this.jobDefinitionRepository = jobDefinitionRepository;
        this.workflowDefinitionRepository = workflowDefinitionRepository;
    }

    @Override
    public SchedulePlan build(SchedulePlanCommand command) {
        JobDefinitionRecord jobDefinition = jobDefinitionRepository.findFirstByTenantIdAndJobCodeAndEnabled(command.tenantId(), command.jobCode(), true);
        WorkflowDefinitionRecord workflowDefinition = workflowDefinitionRepository.findFirstByTenantIdAndWorkflowCodeAndEnabled(command.tenantId(), command.jobCode(), true);
        Map<String, Object> planParams = mergePlanParams(jobDefinition, command.params());

        SchedulePlan plan = new SchedulePlan();
        plan.setTenantId(command.tenantId());
        plan.setJobCode(command.jobCode());
        plan.setBizDate(command.bizDate());
        plan.setJobDefinitionId(jobDefinition == null ? null : jobDefinition.getId());
        plan.setWorkflowDefinitionId(workflowDefinition == null ? null : workflowDefinition.getId());
        plan.setQueueCode(jobDefinition == null ? null : jobDefinition.getQueueCode());
        plan.setWorkerGroup(jobDefinition == null ? null : jobDefinition.getWorkerGroup());
        plan.setWindowCode(jobDefinition == null ? null : jobDefinition.getWindowCode());
        plan.setDefaultWorkerType(jobDefinition == null ? null : jobDefinition.getJobType());
        plan.setPriority(jobDefinition == null ? 5 : jobDefinition.getPriority());
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
        if (jobDefinition != null && jobDefinition.getDefaultParams() != null) {
            merged.putAll(jobDefinition.getDefaultParams());
        }
        if (runtimeParams != null) {
            merged.putAll(runtimeParams);
        }
        return merged;
    }

    private int resolvePartitionCount(JobDefinitionRecord jobDefinition, Map<String, Object> params) {
        String shardStrategy = jobDefinition == null || !StringUtils.hasText(jobDefinition.getShardStrategy())
                ? "NONE"
                : jobDefinition.getShardStrategy().trim().toUpperCase();
        return switch (shardStrategy) {
            case "STATIC" -> clampPartitionCount(firstPositiveInt(
                    params.get("partitionCount"),
                    params.get("staticPartitionCount"),
                    params.get("shardCount"),
                    params.get("fixedShardCount")
            ));
            case "DYNAMIC", "AUTO" -> clampPartitionCount(resolveDynamicPartitionCount(params));
            default -> 1;
        };
    }

    private int resolveDynamicPartitionCount(Map<String, Object> params) {
        int explicitPartitionCount = firstPositiveInt(
                params.get("partitionCount"),
                params.get("estimatedPartitionCount"),
                params.get("suggestedPartitionCount"),
                params.get("shardCount")
        );
        if (explicitPartitionCount > 0) {
            return explicitPartitionCount;
        }
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
        return 1;
    }

    private int clampPartitionCount(int partitionCount) {
        return partitionCount <= 0 ? 1 : Math.min(partitionCount, 256);
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
