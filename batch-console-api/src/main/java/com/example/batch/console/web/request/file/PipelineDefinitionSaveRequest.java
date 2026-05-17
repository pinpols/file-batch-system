package com.example.batch.console.web.request.file;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class PipelineDefinitionSaveRequest {
  @ValidTenantId private String tenantId;

  @NotBlank
  @Size(max = 128)
  private String jobCode;

  @NotBlank
  @Size(max = 256)
  private String pipelineName;

  // 与 DB ck_pipeline_definition_type CHECK (V74) 对齐，避免任意值 INSERT 撞约束 → 500。
  @NotBlank
  @Size(max = 32)
  @Pattern(
      regexp = "^(IMPORT|EXPORT|PROCESS|DISPATCH)$",
      message = "pipelineType must be one of: IMPORT/EXPORT/PROCESS/DISPATCH")
  private String pipelineType;

  @Size(max = 64)
  private String bizType;

  @Size(max = 128)
  private String workerGroup;

  private Boolean enabled;

  @Size(max = 512)
  private String description;

  @Valid private List<StepItem> steps;

  @Data
  public static class StepItem {
    @NotBlank
    @Size(max = 128)
    private String stepCode;

    @NotBlank
    @Size(max = 256)
    private String stepName;

    @NotBlank
    @Size(max = 64)
    private String stageCode;

    @Min(0)
    private Integer stepOrder;

    @NotBlank
    @Size(max = 128)
    private String implCode;

    private String stepParams;

    @Min(0)
    private Integer timeoutSeconds;

    @Size(max = 32)
    private String retryPolicy;

    @Min(0)
    private Integer retryMaxCount;

    private Boolean enabled;
  }
}
