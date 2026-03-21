package com.example.batch.orchestrator.domain.pipeline;

import java.util.Set;

public class StepDefinition {

    private String stepCode;
    private String stepName;
    private String stepType;
    private Integer stepOrder;
    private String workerType;
    private Set<String> capabilityTags;
    private String resourceProfile;
    private Boolean enabled;

    public String getStepCode() {
        return stepCode;
    }

    public void setStepCode(String stepCode) {
        this.stepCode = stepCode;
    }

    public String getStepName() {
        return stepName;
    }

    public void setStepName(String stepName) {
        this.stepName = stepName;
    }

    public String getStepType() {
        return stepType;
    }

    public void setStepType(String stepType) {
        this.stepType = stepType;
    }

    public Integer getStepOrder() {
        return stepOrder;
    }

    public void setStepOrder(Integer stepOrder) {
        this.stepOrder = stepOrder;
    }

    public String getWorkerType() {
        return workerType;
    }

    public void setWorkerType(String workerType) {
        this.workerType = workerType;
    }

    public Set<String> getCapabilityTags() {
        return capabilityTags;
    }

    public void setCapabilityTags(Set<String> capabilityTags) {
        this.capabilityTags = capabilityTags;
    }

    public String getResourceProfile() {
        return resourceProfile;
    }

    public void setResourceProfile(String resourceProfile) {
        this.resourceProfile = resourceProfile;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
