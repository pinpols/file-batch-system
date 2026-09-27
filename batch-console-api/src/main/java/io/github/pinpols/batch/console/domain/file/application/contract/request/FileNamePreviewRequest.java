package io.github.pinpols.batch.console.domain.file.application.contract.request;

import io.github.pinpols.batch.common.validation.ValidBizDate;
import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 导出文件命名规则预览请求。 */
@Data
public class FileNamePreviewRequest {

  @ValidTenantId
  private String tenantId;

  @Size(max = 512)
  private String namingRule;

  @NotBlank
  @Size(max = 32)
  private String fileFormatType;

  @Size(max = 64)
  private String bizType;

  @ValidBizDate
  @NotBlank
  private String bizDate;

  @Size(max = 128)
  private String batchNo;

  @Size(max = 64)
  private String region;

  @Size(max = 32)
  private String version;
}
