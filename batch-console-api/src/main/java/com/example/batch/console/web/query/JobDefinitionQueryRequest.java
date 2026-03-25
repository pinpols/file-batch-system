package com.example.batch.console.web.query;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class JobDefinitionQueryRequest {

    @ValidTenantId
    private String tenantId;
    @Size(max = 128, message = "jobCode too long (max 128)")
    private String jobCode;
    @Size(max = 64, message = "jobType too long (max 64)")
    private String jobType;
    private Boolean enabled;
}
