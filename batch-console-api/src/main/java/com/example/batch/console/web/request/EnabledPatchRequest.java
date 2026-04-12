package com.example.batch.console.web.request;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 启用/禁用状态局部更新请求。 */
@Data
public class EnabledPatchRequest {

  @ValidTenantId private String tenantId;

  @NotNull private Boolean enabled;
}
