package com.example.batch.console.domain.query;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FileArrivalGroupQueryRequest {

    @NotBlank
    private String tenantId;
    private String fileGroupCode;
    private String arrivalState;
}
