package io.github.pinpols.batch.console.application.config;

import io.github.pinpols.batch.console.application.contract.response.ExcelDownload;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigPackageExcelApplyRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigPackageExcelApplyResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigPackageExcelGuideResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigPackageExcelPreviewResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigPackageExcelUploadResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

/** 租户配置包 Excel（11 Sheet）：资源队列、日历、窗口、作业、文件、流水线和工作流配置。 */
public interface TenantConfigPackageExcelService {

  ExcelDownload exportPackage(String tenantId);

  ExcelDownload downloadTemplate();

  ExcelDownload downloadScenarioTemplate(List<String> scenarios);

  ExcelDownload downloadSampleTemplate(
      String scenario, List<String> scenarios);

  TenantConfigPackageExcelGuideResponse guide();

  TenantConfigPackageExcelUploadResponse upload(MultipartFile file, String tenantId)
      throws IOException;

  TenantConfigPackageExcelPreviewResponse preview(String uploadToken);

  /** 内联编辑回写:把出错行被改的单元格合并进上传会话,重校验后返回新预览(不落库)。 */
  TenantConfigPackageExcelPreviewResponse patchRow(
      String uploadToken,
      String previewToken,
      String sheetName,
      int rowNo,
      Map<String, String> values);

  ExcelDownload downloadPreviewWorkbook(String uploadToken);

  TenantConfigPackageExcelApplyResponse apply(
      String uploadToken, TenantConfigPackageExcelApplyRequest request);
}
