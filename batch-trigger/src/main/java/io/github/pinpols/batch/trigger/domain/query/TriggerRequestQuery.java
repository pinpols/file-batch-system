package io.github.pinpols.batch.trigger.domain.query;

import lombok.Data;

import io.github.pinpols.batch.common.model.PageRequest;

@Data
public class TriggerRequestQuery {

    private String tenantId;
    private String jobCode;
    private String triggerType;
    private String requestStatus;
    private PageRequest pageRequest;
}
