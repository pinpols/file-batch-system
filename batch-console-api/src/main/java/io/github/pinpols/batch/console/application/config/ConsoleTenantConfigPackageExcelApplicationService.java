package io.github.pinpols.batch.console.application.config;

import io.github.pinpols.batch.console.web.request.config.TenantConfigPackageExcelApplyRequest;
import io.github.pinpols.batch.console.web.response.config.TenantConfigPackageExcelApplyResponse;
import io.github.pinpols.batch.console.web.response.config.TenantConfigPackageExcelPreviewResponse;
import io.github.pinpols.batch.console.web.response.config.TenantConfigPackageExcelUploadResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 租户配置包 Excel（8 Sheet）：job_definition / file_channel / alert_routing / pipeline / workflow。 */
public interface ConsoleTenantConfigPackageExcelApplicationService {

  ResponseEntity<StreamingResponseBody> exportPackage(String tenantId);

  ResponseEntity<StreamingResponseBody> downloadTemplate();

  TenantConfigPackageExcelUploadResponse upload(MultipartFile file, String tenantId)
      throws IOException;

  TenantConfigPackageExcelPreviewResponse preview(String uploadToken);

  /** 内联编辑回写:把出错行被改的单元格合并进上传会话,重校验后返回新预览(不落库)。 */
  TenantConfigPackageExcelPreviewResponse patchRow(
      String uploadToken, String sheetName, int rowNo, Map<String, String> values);

  ResponseEntity<StreamingResponseBody> downloadPreviewWorkbook(String uploadToken);

  TenantConfigPackageExcelApplyResponse apply(
      String uploadToken, TenantConfigPackageExcelApplyRequest request);
}
