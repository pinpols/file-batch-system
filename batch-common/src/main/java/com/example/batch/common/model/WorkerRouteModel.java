package com.example.batch.common.model;

import java.util.Set;

public class WorkerRouteModel {

    private String workerId;
    private String workerType;
    private Set<String> capabilityTags;
    private String resourceProfile;
    private Integer priority;
    private Boolean available;

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
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

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Boolean getAvailable() {
        return available;
    }

    public void setAvailable(Boolean available) {
        this.available = available;
    }
}
