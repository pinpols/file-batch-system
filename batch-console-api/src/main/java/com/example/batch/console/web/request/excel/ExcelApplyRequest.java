package com.example.batch.console.web.request.excel;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** 所有 Excel apply 端点的统一请求体。 */
@Data
public class ExcelApplyRequest {

  @Size(max = 512, message = "reason too long (max 512)")
  private String reason;
}
