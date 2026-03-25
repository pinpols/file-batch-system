package com.example.batch.console.web.request;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PresignDownloadFileRequest {

    @ValidTenantId
    private String tenantId;
    @NotNull
    private Long fileId;
    /** Required when file template sets download_requires_approval or content_encryption_enabled. */
    @Size(max = 64, message = "approvalId too long (max 64)")
    private String approvalId;
    @Size(max = 512, message = "reason too long (max 512)")
    private String reason;
}
