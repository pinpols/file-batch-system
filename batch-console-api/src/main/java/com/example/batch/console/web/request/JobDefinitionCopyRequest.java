package com.example.batch.console.web.request;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 克隆作业定义请求：必须指定 newJobCode，其余字段为可选覆盖。
 */
@Data
public class JobDefinitionCopyRequest {
    @ValidTenantId
    private String tenantId;
    @NotBlank
    @Size(max = 128)
    private String newJobCode;
    @Size(max = 256)
    private String jobName;
    private String workerGroup;
    private String queueCode;
    private String calendarCode;
    private String windowCode;
    private String scheduleExpr;
    private String retryPolicy;
    private Integer retryMaxCount;
    private Integer timeoutSeconds;
    private Boolean enabled;
    private String description;
}
