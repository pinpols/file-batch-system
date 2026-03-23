package com.example.batch.console.web.query;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class JobDefinitionQueryRequest {

    @NotBlank
    private String tenantId;
    private String jobCode;
    private String jobType;
    private Boolean enabled;
}
