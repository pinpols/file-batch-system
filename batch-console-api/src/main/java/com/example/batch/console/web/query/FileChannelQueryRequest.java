package com.example.batch.console.web.query;

import lombok.Data;

@Data
public class FileChannelQueryRequest extends PageQueryRequest {

    private String tenantId;
    private String channelCode;
    private String channelType;
    private Boolean enabled = true;
}
