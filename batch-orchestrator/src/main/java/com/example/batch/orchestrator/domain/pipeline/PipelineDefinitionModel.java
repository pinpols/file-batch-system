package com.example.batch.orchestrator.domain.pipeline;

import java.util.List;

public class PipelineDefinitionModel {

    private Long id;
    private String tenantId;
    private String jobCode;
    private String pipelineName;
    private String pipelineType;
    private String defaultWorkerType;
    private Boolean enabled;
    private List<StepDefinitionModel> steps;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public String getPipelineName() {
        return pipelineName;
    }

    public void setPipelineName(String pipelineName) {
        this.pipelineName = pipelineName;
    }

    public String getPipelineType() {
        return pipelineType;
    }

    public void setPipelineType(String pipelineType) {
        this.pipelineType = pipelineType;
    }

    public String getDefaultWorkerType() {
        return defaultWorkerType;
    }

    public void setDefaultWorkerType(String defaultWorkerType) {
        this.defaultWorkerType = defaultWorkerType;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public List<StepDefinitionModel> getSteps() {
        return steps;
    }

    public void setSteps(List<StepDefinitionModel> steps) {
        this.steps = steps;
    }
}
