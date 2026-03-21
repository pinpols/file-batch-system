package com.example.batch.orchestrator.domain.pipeline;

import java.util.List;

public class PipelineDefinitionModel {

    private Long id;
    private String tenantId;
    private String pipelineCode;
    private String pipelineName;
    private String pipelineType;
    private String defaultWorkerType;
    private Boolean enabled;
    private List<StepDefinitionModel> steps;
}
