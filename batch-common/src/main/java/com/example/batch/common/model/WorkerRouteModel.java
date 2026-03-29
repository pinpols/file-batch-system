package com.example.batch.common.model;

import java.util.Set;

public class WorkerRouteModel {

    /**
     * Selected worker code for routing or assignment.
     *
     * <p>Kept separate from the runtime worker instance id used in logs and
     * heartbeats. Existing callers may still access it via {@code workerId}
     * compatibility accessors.
     */
    private String workerCode;
    private String workerType;
    private Set<String> capabilityTags;
    private String resourceProfile;
    private Integer priority;
    private Boolean available;

    public String getWorkerId() {
        return workerCode;
    }

    public void setWorkerId(String workerId) {
        this.workerCode = workerId;
    }

    public String getWorkerCode() {
        return workerCode;
    }

    public void setWorkerCode(String workerCode) {
        this.workerCode = workerCode;
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
