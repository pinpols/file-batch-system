package com.example.batch.console.application;

import com.example.batch.console.web.request.TenantConfigPackageExcelApplyRequest;
import com.example.batch.console.web.response.TenantConfigPackageExcelApplyResponse;
import com.example.batch.console.web.response.TenantConfigPackageExcelPreviewResponse;
import com.example.batch.console.web.response.TenantConfigPackageExcelUploadResponse;
import java.io.IOException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/** 租户配置包 Excel（8 Sheet）：job_definition / file_channel / alert_routing / pipeline / workflow。 */
public interface ConsoleTenantConfigPackageExcelApplicationService {

  ResponseEntity<InputStreamResource> exportPackage(String tenantId);

  ResponseEntity<InputStreamResource> downloadTemplate();

  TenantConfigPackageExcelUploadResponse upload(MultipartFile file) throws IOException;

  TenantConfigPackageExcelPreviewResponse preview(String uploadToken);

  ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken);

  TenantConfigPackageExcelApplyResponse apply(
      String uploadToken, TenantConfigPackageExcelApplyRequest request);
}
