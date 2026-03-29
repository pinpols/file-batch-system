package com.example.batch.orchestrator.domain.pipeline;

import com.example.batch.common.model.WorkerRouteModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrator-side workflow execution context.
 *
 * <p>This is the canonical orchestrator pipeline context. It carries
 * workflow-specific fields such as pipeline definition, biz date, trace id,
 * and collected step results.
 */
public class ExecutionContext {

    private String tenantId;
    private String jobCode;
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
        return jobCode;
    }

    public void setPipelineCode(String pipelineCode) {
        this.jobCode = pipelineCode;
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
