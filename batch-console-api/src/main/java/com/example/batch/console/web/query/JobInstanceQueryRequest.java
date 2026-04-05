package com.example.batch.console.web.query;

import lombok.Data;

@Data
public class JobInstanceQueryRequest extends PageQueryRequest {

    private String tenantId;
    private String jobCode;
    private String instanceNo;
    private String instanceStatus;
    private String bizDate;
    private String traceId;
    private String startDate;
    private String endDate;
}
