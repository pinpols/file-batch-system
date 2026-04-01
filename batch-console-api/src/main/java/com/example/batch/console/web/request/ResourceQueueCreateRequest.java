package com.example.batch.console.web.request;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResourceQueueCreateRequest {
    @ValidTenantId
    private String tenantId;
    @NotBlank @Size(max = 128)
    private String queueCode;
    @Size(max = 256)
    private String queueName;
    @NotBlank @Size(max = 32)
    private String queueType;
    private Integer maxRunningJobs;
    private Integer maxRunningPartitions;
    private Integer maxQps;
    @Size(max = 128)
    private String workerGroup;
    @Size(max = 64)
    private String resourceTag;
    @Size(max = 32)
    private String priorityPolicy;
    private Integer fairShareWeight;
    private Boolean enabled;
    @Size(max = 512)
    private String description;
}
