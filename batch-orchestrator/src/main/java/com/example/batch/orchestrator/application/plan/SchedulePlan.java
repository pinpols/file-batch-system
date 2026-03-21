package com.example.batch.orchestrator.application.plan;

import com.example.batch.common.model.WorkerRouteModel;
import java.util.ArrayList;
import java.util.List;

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

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getJobCode() {
        return jobCode;
    }

    public void setJobCode(String jobCode) {
        this.jobCode = jobCode;
    }

    public String getBizDate() {
        return bizDate;
    }

    public void setBizDate(String bizDate) {
        this.bizDate = bizDate;
    }

    public Long getJobDefinitionId() {
        return jobDefinitionId;
    }

    public void setJobDefinitionId(Long jobDefinitionId) {
        this.jobDefinitionId = jobDefinitionId;
    }

    public Long getWorkflowDefinitionId() {
        return workflowDefinitionId;
    }

    public void setWorkflowDefinitionId(Long workflowDefinitionId) {
        this.workflowDefinitionId = workflowDefinitionId;
    }

    public String getQueueCode() {
        return queueCode;
    }

    public void setQueueCode(String queueCode) {
        this.queueCode = queueCode;
    }

    public String getWorkerGroup() {
        return workerGroup;
    }

    public void setWorkerGroup(String workerGroup) {
        this.workerGroup = workerGroup;
    }

    public String getWindowCode() {
        return windowCode;
    }

    public void setWindowCode(String windowCode) {
        this.windowCode = windowCode;
    }

    public String getDefaultWorkerType() {
        return defaultWorkerType;
    }

    public void setDefaultWorkerType(String defaultWorkerType) {
        this.defaultWorkerType = defaultWorkerType;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Integer getPartitionCount() {
        return partitionCount;
    }

    public void setPartitionCount(Integer partitionCount) {
        this.partitionCount = partitionCount;
    }

    public List<PartitionPlan> getPartitions() {
        return partitions;
    }

    public void setPartitions(List<PartitionPlan> partitions) {
        this.partitions = partitions;
    }

    public WorkerRouteModel getDefaultWorkerRoute() {
        return defaultWorkerRoute;
    }

    public void setDefaultWorkerRoute(WorkerRouteModel defaultWorkerRoute) {
        this.defaultWorkerRoute = defaultWorkerRoute;
    }

    public static class PartitionPlan {

        private Integer partitionNo;
        private String partitionKey;
        private String businessKey;
        private WorkerRouteModel workerRoute;
        private String partitionStatus;

        public Integer getPartitionNo() {
            return partitionNo;
        }

        public void setPartitionNo(Integer partitionNo) {
            this.partitionNo = partitionNo;
        }

        public String getPartitionKey() {
            return partitionKey;
        }

        public void setPartitionKey(String partitionKey) {
            this.partitionKey = partitionKey;
        }

        public String getBusinessKey() {
            return businessKey;
        }

        public void setBusinessKey(String businessKey) {
            this.businessKey = businessKey;
        }

        public WorkerRouteModel getWorkerRoute() {
            return workerRoute;
        }

        public void setWorkerRoute(WorkerRouteModel workerRoute) {
            this.workerRoute = workerRoute;
        }

        public String getPartitionStatus() {
            return partitionStatus;
        }

        public void setPartitionStatus(String partitionStatus) {
            this.partitionStatus = partitionStatus;
        }
    }
}
