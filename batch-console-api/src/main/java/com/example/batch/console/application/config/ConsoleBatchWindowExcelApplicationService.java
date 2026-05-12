package com.example.batch.console.application.config;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;

/** 批处理窗口 Excel 模板与导出应用服务。 */
public interface ConsoleBatchWindowExcelApplicationService {

  /** 导出批处理窗口配置为 Excel。 */
  ResponseEntity<InputStreamResource> exportBatchWindows(String tenantId);

  /** 下载空白模板。 */
  ResponseEntity<InputStreamResource> downloadTemplate();
}
