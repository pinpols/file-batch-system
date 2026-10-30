package io.github.pinpols.batch.console.application.contract.request.config;

import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigCopyRequest.ConfigType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import lombok.Data;

/** 同步配置导出；可选类型集合为空表示全部，集合元素保持非空约束。 */
@Data
public class ConfigSyncExportRequest {

  @NotBlank
  @Size(max = 64)
  private String sourceTenantId;

  @NotBlank
  @Size(max = 64)
  private String sourceEnv;

  @NotBlank
  @Size(max = 64)
  private String targetEnv;

  private Set<@NotNull ConfigType> configTypes;
}
