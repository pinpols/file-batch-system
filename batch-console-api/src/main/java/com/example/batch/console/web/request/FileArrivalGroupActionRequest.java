package com.example.batch.console.web.request;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class FileArrivalGroupActionRequest {

    @ValidTenantId
    private String tenantId;
    @NotBlank
    @Size(max = 128, message = "fileGroupCode too long (max 128)")
    private String fileGroupCode;
    @NotBlank
    @Size(max = 32, message = "action too long (max 32)")
    private String action;
    @Size(max = 512, message = "reason too long (max 512)")
    private String reason;
    private Long extendWaitSeconds;
}
