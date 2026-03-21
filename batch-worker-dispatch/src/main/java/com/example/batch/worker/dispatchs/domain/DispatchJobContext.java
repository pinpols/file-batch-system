package com.example.batch.worker.dispatchs.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

@Data
public class DispatchJobContext {

    private String tenantId;
    private String jobCode;
    private String bizDate;
    private String dispatchId;
    private String workerId;
    private String rawPayload;
    private Map<String, Object> attributes = new LinkedHashMap<>();
}
