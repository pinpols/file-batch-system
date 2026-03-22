package com.example.batch.console.domain.query;

import lombok.Data;

@Data
public class FilePipelineQueryRequest {

    private String tenantId;
    private Long fileId;
    private Long pipelineInstanceId;
    private String pipelineType;
    private String runStatus;
    private String traceId;
    private String fromTime;
    private String toTime;
}
