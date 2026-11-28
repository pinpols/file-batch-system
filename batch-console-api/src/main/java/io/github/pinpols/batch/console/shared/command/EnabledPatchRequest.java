package io.github.pinpols.batch.console.shared.command;

import lombok.Data;

import io.github.pinpols.batch.common.validation.ValidTenantId;

import jakarta.validation.constraints.NotNull;

/** 启用/禁用状态局部更新请求。 */
@Data
public class EnabledPatchRequest {

    @ValidTenantId
    private String tenantId;

    @NotNull
    private Boolean enabled;
}
