package com.example.batch.console.domain.job.web.request;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

/** 批量启用/禁用状态局部更新请求。 */
@Data
public class BatchEnabledPatchRequest {

  @ValidTenantId private String tenantId;

  @NotNull private Boolean enabled;

  @NotEmpty
  @Size(max = 200)
  private List<Long> ids;
}
