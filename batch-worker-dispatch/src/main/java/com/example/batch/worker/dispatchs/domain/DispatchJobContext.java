package com.example.batch.worker.dispatchs.domain;

import com.example.batch.worker.core.support.ExecutionContext;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/**
 * 分发任务执行上下文，贯穿整个 dispatch pipeline 的状态载体。
 */
@Data
public class DispatchJobContext implements ExecutionContext {

    private String tenantId;
    private String jobCode;
    private String bizDate;
    private String dispatchId;
    private String workerId;
    private String rawPayload;
    private Map<String, Object> attributes = new LinkedHashMap<>();
}
