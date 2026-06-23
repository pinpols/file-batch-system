package io.github.pinpols.batch.console.web.request.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.Data;

/** 租户配置包 Excel 预览出错行内联编辑请求:定位 (sheet, rowNo) + 改动的单元格值。 */
@Data
public class TenantConfigPackageExcelPatchRequest {

  /** sheet 名(后端 validator SHEET 常量,如 job_definition / file_channel_config)。 */
  @NotBlank private String sheetName;

  /** 行号(与预览返回一致;数据行从 2 起,1 为表头)。 */
  @Min(2)
  private int rowNo;

  /** 改动的单元格:列键 → 新值。仅合并该行已有的列键,未知键忽略。 */
  private Map<String, String> values;
}
