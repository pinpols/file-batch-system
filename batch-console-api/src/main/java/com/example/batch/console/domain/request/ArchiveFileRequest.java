package com.example.batch.console.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ArchiveFileRequest {

    @NotBlank
    private String tenantId;
    @NotNull
    private Long fileId;
    private String reason;
}
