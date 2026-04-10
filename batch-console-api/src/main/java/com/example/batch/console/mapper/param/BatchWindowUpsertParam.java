package com.example.batch.console.mapper.param;

import lombok.Data;

@Data
public class BatchWindowUpsertParam {

    private String tenantId;
    private String windowCode;
    private String windowName;
    private String timezone;
    private String startTime;
    private String endTime;
    private String endStrategy;
    private String outOfWindowAction;
    private Boolean allowCrossDay;
    private Boolean enabled;
    private String description;
}
