package com.example.batch.orchestrator.application.plan;

import com.example.batch.common.model.WorkerRouteModel;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class SchedulePlan {

    private String tenantId;
    private String jobCode;
    private String bizDate;
    private Long jobDefinitionId;
    private Long workflowDefinitionId;
    private String queueCode;
    private String workerGroup;
    private String windowCode;
    private String defaultWorkerType;
    private Integer priority;
    private Integer partitionCount;
    private List<PartitionPlan> partitions = new ArrayList<>();
    private WorkerRouteModel defaultWorkerRoute;
    @Data
    public static class PartitionPlan {

        private Integer partitionNo;
        private String partitionKey;
        private String businessKey;
        private WorkerRouteModel workerRoute;
        private String partitionStatus;
    }
}
