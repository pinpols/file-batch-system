package com.example.batch.orchestrator.domain.pipeline;

import com.example.batch.common.model.WorkerRouteModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PipelineContext {

    private String tenantId;
    private String pipelineCode;
    private String bizDate;
    private String traceId;
    private PipelineDefinition pipelineDefinition;
    private Map<String, Object> attributes = new LinkedHashMap<>();
    private List<StepResult> stepResults;
    private WorkerRouteModel defaultWorkerRoute;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getPipelineCode() {
        return pipelineCode;
    }

    public void setPipelineCode(String pipelineCode) {
        this.pipelineCode = pipelineCode;
    }

    public String getBizDate() {
        return bizDate;
    }

    public void setBizDate(String bizDate) {
        this.bizDate = bizDate;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public PipelineDefinition getPipelineDefinition() {
        return pipelineDefinition;
    }

    public void setPipelineDefinition(PipelineDefinition pipelineDefinition) {
        this.pipelineDefinition = pipelineDefinition;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    public List<StepResult> getStepResults() {
        return stepResults;
    }

    public void setStepResults(List<StepResult> stepResults) {
        this.stepResults = stepResults;
    }

    public WorkerRouteModel getDefaultWorkerRoute() {
        return defaultWorkerRoute;
    }

    public void setDefaultWorkerRoute(WorkerRouteModel defaultWorkerRoute) {
        this.defaultWorkerRoute = defaultWorkerRoute;
    }
}
