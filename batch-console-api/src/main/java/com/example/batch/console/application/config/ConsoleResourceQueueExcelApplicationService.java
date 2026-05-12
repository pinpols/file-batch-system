package com.example.batch.console.application.config;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;

/** 资源队列 Excel 模板与导出应用服务。 */
public interface ConsoleResourceQueueExcelApplicationService {

  /** 导出资源队列配置为 Excel。 */
  ResponseEntity<InputStreamResource> exportResourceQueues(
      String tenantId, String queueCode, String queueType, Boolean enabled);

  /** 下载空白模板。 */
  ResponseEntity<InputStreamResource> downloadTemplate();
}
