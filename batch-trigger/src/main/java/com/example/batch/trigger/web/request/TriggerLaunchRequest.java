package com.example.batch.trigger.web.request;

import com.example.batch.common.enums.TriggerType;
import lombok.Data;
import java.time.LocalDate;
import java.util.Map;

@Data
public class TriggerLaunchRequest {

    private String tenantId;
    private String jobCode;
    private LocalDate bizDate;
    private TriggerType triggerType;
    private Map<String, Object> params;
}
