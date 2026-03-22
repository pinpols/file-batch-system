package com.example.batch.console.domain.query;

import lombok.Data;

@Data
public class FileChannelQueryRequest {

    private String tenantId;
    private String channelCode;
    private String channelType;
    private Boolean enabled;
}
