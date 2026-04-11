package com.example.batch.orchestrator.domain.pipeline;

import lombok.Data;

import java.util.List;

@Data
public class PipelineDefinitionModel {

    private Long id;
    private String tenantId;
    private String jobCode;
    private String pipelineName;
    private String pipelineType;
    private String defaultWorkerType;
    private Boolean enabled;
    private List<StepDefinitionModel> steps;

    public String getPipelineCode() {
        return jobCode;
    }

    public void setPipelineCode(String pipelineCode) {
        this.jobCode = pipelineCode;
    }
}
