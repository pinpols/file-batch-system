package io.github.pinpols.batch.console.domain.job.application.contract.query;

import lombok.Data;
import lombok.EqualsAndHashCode;

import io.github.pinpols.batch.console.application.contract.query.PageQueryRequest;

@EqualsAndHashCode(callSuper = false)
@Data
public class JobStepInstanceQueryRequest extends PageQueryRequest {

    private String tenantId;
    private Long jobInstanceId;
    private Long jobPartitionId;
    private String stepCode;
    private String stepStatus;
}
