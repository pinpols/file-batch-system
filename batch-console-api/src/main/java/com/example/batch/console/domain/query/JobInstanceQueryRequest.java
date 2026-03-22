package com.example.batch.console.domain.query;

import lombok.Data;

@Data
public class JobInstanceQueryRequest {

    private String tenantId;
    private String jobCode;
    private String instanceNo;
    private String instanceStatus;
    private String bizDate;
    private String traceId;
}
