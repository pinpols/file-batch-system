package com.example.batch.console.application.config;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;

/** 工作日历 Excel 模板与导出应用服务。 */
public interface ConsoleBusinessCalendarExcelApplicationService {

  /** 导出工作日历配置为 Excel（含日历与假日两个 sheet）。 */
  ResponseEntity<InputStreamResource> exportBusinessCalendars(String tenantId);

  /** 下载空白模板。 */
  ResponseEntity<InputStreamResource> downloadTemplate();
}
