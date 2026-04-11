package com.example.batch.console.web.request;

import com.example.batch.common.validation.ValidTenantId;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

import java.util.List;

@Data
public class PipelineDefinitionSaveRequest {
    @ValidTenantId private String tenantId;

    @NotBlank
    @Size(max = 128)
    private String jobCode;

    @NotBlank
    @Size(max = 256)
    private String pipelineName;

    @NotBlank
    @Size(max = 32)
    private String pipelineType;

    @Size(max = 64)
    private String bizType;

    @Size(max = 128)
    private String workerGroup;

    private Boolean enabled;

    @Size(max = 512)
    private String description;

    @Valid private List<StepItem> steps;

    @Data
    public static class StepItem {
        @NotBlank
        @Size(max = 128)
        private String stepCode;

        @NotBlank
        @Size(max = 256)
        private String stepName;

        @NotBlank
        @Size(max = 64)
        private String stageCode;

        @Min(0)
        private Integer stepOrder;

        @NotBlank
        @Size(max = 128)
        private String implCode;

        private String stepParams;

        @Min(0)
        private Integer timeoutSeconds;

        @Size(max = 32)
        private String retryPolicy;

        @Min(0)
        private Integer retryMaxCount;

        private Boolean enabled;
    }
}
