package com.example.batch.console.web.query;

import lombok.Data;

@Data
public class FilePipelineQueryRequest extends PageQueryRequest {

    private String tenantId;
    private Long fileId;
    private Long pipelineInstanceId;
    private String pipelineType;
    private String runStatus;
    private String traceId;
    private String fromTime;
    private String toTime;
}
