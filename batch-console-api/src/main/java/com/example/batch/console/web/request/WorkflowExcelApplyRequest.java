package com.example.batch.console.web.request;

import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class WorkflowExcelApplyRequest {

    @Size(max = 512, message = "reason too long (max 512)")
    private String reason;
}
