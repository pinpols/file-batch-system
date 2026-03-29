package com.example.batch.orchestrator.domain.pipeline;

import java.util.List;

/**
 * Orchestrator-side pipeline definition model.
 *
 * <p>The canonical business key is {@code jobCode}. {@code pipelineCode} is
 * kept as a compatibility alias for older call sites and serialized payloads.
 */
public class PipelineDefinition {

    private String jobCode;
    private String pipelineName;
    private String pipelineType;
    private String defaultWorkerType;
    private Boolean enabled;
    private List<StepDefinition> steps;

    public String getPipelineCode() {
        return jobCode;
    }

    public void setPipelineCode(String pipelineCode) {
        this.jobCode = pipelineCode;
    }

    public void setJobCode(String jobCode) {
        this.jobCode = jobCode;
    }

    public String getJobCode() {
        return jobCode;
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
