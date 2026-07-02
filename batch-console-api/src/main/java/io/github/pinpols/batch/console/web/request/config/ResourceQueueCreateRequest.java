package io.github.pinpols.batch.console.web.request.config;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResourceQueueCreateRequest {
  @ValidTenantId private String tenantId;

  @NotBlank
  @Size(max = 128)
  @Pattern(
      regexp = "^[a-zA-Z][a-zA-Z0-9_-]{0,127}$",
      message =
          "queueCode must start with a letter and contain only letters, digits, underscore or"
              + " hyphen")
  private String queueCode;

  @Size(max = 256)
  private String queueName;

  @NotBlank
  @Size(max = 32)
  private String queueType;

  @Min(value = 0, message = "maxRunningJobs must be >= 0")
  private Integer maxRunningJobs;

  @Min(value = 0, message = "maxRunningPartitions must be >= 0")
  private Integer maxRunningPartitions;

  @Min(value = 0, message = "maxQps must be >= 0")
  private Integer maxQps;

  @Size(max = 128)
  private String workerGroup;

  @Size(max = 64)
  private String resourceTag;

  @Size(max = 32)
  private String priorityPolicy;

  @Min(value = 1, message = "fairShareWeight must be >= 1")
  private Integer fairShareWeight;

  private Boolean enabled;

  @Size(max = 512)
  private String description;
}
