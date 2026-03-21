package com.example.batch.orchestrator.domain.pipeline;

import java.util.List;

public class PipelineDefinition {

    private String pipelineCode;
    private String pipelineName;
    private String pipelineType;
    private String defaultWorkerType;
    private Boolean enabled;
    private List<StepDefinition> steps;

    public String getPipelineCode() {
        return pipelineCode;
    }

    public void setPipelineCode(String pipelineCode) {
        this.pipelineCode = pipelineCode;
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

    public List<StepDefinition> getSteps() {
        return steps;
    }

    public void setSteps(List<StepDefinition> steps) {
        this.steps = steps;
    }
}
