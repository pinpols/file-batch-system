package com.example.batch.console.web.query;

import com.example.batch.common.validation.ValidTenantId;

import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class JobDefinitionQueryRequest extends PageQueryRequest {

    @ValidTenantId private String tenantId;

    @Size(max = 128, message = "jobCode too long (max 128)")
    private String jobCode;

    @Size(max = 256, message = "jobName too long (max 256)")
    private String jobName;

    @Size(max = 64, message = "jobType too long (max 64)")
    private String jobType;

    @Size(max = 64, message = "workerGroup too long (max 64)")
    private String workerGroup;

    @Size(max = 64, message = "queueCode too long (max 64)")
    private String queueCode;

    @Size(max = 64, message = "scheduleType too long (max 64)")
    private String scheduleType;

    private Boolean enabled;
}
