package com.example.batch.orchestrator.domain.pipeline;

import lombok.Data;

import java.util.Set;

@Data
public class StepDefinitionModel {

    private Long id;
    private String stepCode;
    private String stepName;
    private String stepType;
    private String workerType;
    private Set<String> capabilityTags;
    private String resourceProfile;
    private Integer stepOrder;
    private Boolean enabled;
}
