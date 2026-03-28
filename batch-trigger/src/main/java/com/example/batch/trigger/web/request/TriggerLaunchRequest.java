package com.example.batch.trigger.web.request;

import com.example.batch.common.enums.TriggerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDate;
import java.util.Map;

@Data
public class TriggerLaunchRequest {

    @NotBlank(message = "tenantId is required")
    private String tenantId;
    @NotBlank(message = "jobCode is required")
    private String jobCode;
    @NotNull(message = "bizDate is required")
    private LocalDate bizDate;
    @NotNull(message = "triggerType is required")
    private TriggerType triggerType;
    private Map<String, Object> params;
}
