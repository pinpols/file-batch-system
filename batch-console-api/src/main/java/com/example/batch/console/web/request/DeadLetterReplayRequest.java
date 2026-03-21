package com.example.batch.console.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DeadLetterReplayRequest {

    @NotBlank
    private String tenantId;
    @NotNull
    private Long deadLetterId;
}
