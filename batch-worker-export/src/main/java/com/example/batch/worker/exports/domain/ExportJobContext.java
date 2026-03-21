package com.example.batch.worker.exports.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

@Data
public class ExportJobContext {

    private String tenantId;
    private String jobCode;
    private String bizDate;
    private String fileId;
    private String workerId;
    private String rawPayload;
    private Map<String, Object> attributes = new LinkedHashMap<>();
}
