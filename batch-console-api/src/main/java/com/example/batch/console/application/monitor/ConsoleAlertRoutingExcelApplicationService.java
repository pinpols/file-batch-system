package com.example.batch.console.application.monitor;

import com.example.batch.console.web.query.AlertRoutingQueryRequest;
import com.example.batch.console.web.request.excel.ExcelApplyRequest;
import com.example.batch.console.web.response.excel.ExcelApplyResponse;
import com.example.batch.console.web.response.excel.ExcelPreviewResponse;
import com.example.batch.console.web.response.excel.ExcelQuickImportResponse;
import com.example.batch.console.web.response.excel.ExcelUploadResponse;
import com.example.batch.console.web.response.config.ConsoleAlertRoutingResponse;
import java.io.IOException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/** 告警路由 Excel 导入导出应用服务。 */
public interface ConsoleAlertRoutingExcelApplicationService {

  ResponseEntity<InputStreamResource> exportAlertRoutings(AlertRoutingQueryRequest request);

  ResponseEntity<InputStreamResource> downloadTemplate();

  ExcelUploadResponse upload(MultipartFile file) throws IOException;

  ExcelPreviewResponse<ConsoleAlertRoutingResponse> preview(String uploadToken);

  ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken);

  ExcelApplyResponse apply(String uploadToken, ExcelApplyRequest request);

  /** 一键导入：upload + validate + apply 合并为一次调用。 */
  ExcelQuickImportResponse<ConsoleAlertRoutingResponse> quickImport(
      MultipartFile file, String reason, boolean skipInvalid) throws IOException;
}
