package com.example.batch.console.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PresignDownloadFileRequest {

    @NotBlank
    private String tenantId;
    @NotNull
    private Long fileId;
    /** Required when file template sets download_requires_approval or content_encryption_enabled. */
    private String approvalId;
    private String reason;
}
