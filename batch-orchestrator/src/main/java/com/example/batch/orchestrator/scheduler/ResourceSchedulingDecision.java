package com.example.batch.orchestrator.scheduler;

import com.example.batch.common.model.WorkerRouteModel;
import lombok.Data;

@Data
public class ResourceSchedulingDecision {

    private boolean dispatchable;
    private boolean failFast;
    private String reasonCode;
    private String reasonMessage;
    private String queueCode;
    private String workerGroup;
    private Integer priority;
    private String priorityBand;
    private String partitionStatus;
    private String taskStatus;
    private WorkerRouteModel route;
}
