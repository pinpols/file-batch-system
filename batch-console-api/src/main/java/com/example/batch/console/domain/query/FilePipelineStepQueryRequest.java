package com.example.batch.console.domain.query;

import lombok.Data;

@Data
public class FilePipelineStepQueryRequest {

    private Long pipelineInstanceId;
    private String stepCode;
    private String stageCode;
    private String stepStatus;
}
