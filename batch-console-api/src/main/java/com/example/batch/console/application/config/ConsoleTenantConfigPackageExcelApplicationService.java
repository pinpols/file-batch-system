package com.example.batch.console.application.config;

import com.example.batch.console.web.request.config.TenantConfigPackageExcelApplyRequest;
import com.example.batch.console.web.response.config.TenantConfigPackageExcelApplyResponse;
import com.example.batch.console.web.response.config.TenantConfigPackageExcelPreviewResponse;
import com.example.batch.console.web.response.config.TenantConfigPackageExcelUploadResponse;
import java.io.IOException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/** 租户配置包 Excel（8 Sheet）：job_definition / file_channel / alert_routing / pipeline / workflow。 */
public interface ConsoleTenantConfigPackageExcelApplicationService {

  ResponseEntity<InputStreamResource> exportPackage(String tenantId);

  ResponseEntity<InputStreamResource> downloadTemplate();

  TenantConfigPackageExcelUploadResponse upload(MultipartFile file, String tenantId)
      throws IOException;

  TenantConfigPackageExcelPreviewResponse preview(String uploadToken);

  ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken);

  TenantConfigPackageExcelApplyResponse apply(
      String uploadToken, TenantConfigPackageExcelApplyRequest request);
}
