package io.github.pinpols.batch.console.domain.file.application.contract.query;

import lombok.Data;
import lombok.EqualsAndHashCode;

import io.github.pinpols.batch.console.application.contract.query.PageQueryRequest;

@EqualsAndHashCode(callSuper = false)
@Data
public class FileChannelQueryRequest extends PageQueryRequest {

    private String tenantId;
    private String channelCode;
    private String channelType;
    private Boolean enabled = true;
}
