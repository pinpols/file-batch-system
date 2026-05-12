package com.example.batch.console.application.monitor;

import com.example.batch.console.web.query.AlertRoutingQueryRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;

/** 告警路由 Excel 模板与导出应用服务。 */
public interface ConsoleAlertRoutingExcelApplicationService {

  ResponseEntity<InputStreamResource> exportAlertRoutings(AlertRoutingQueryRequest request);

  ResponseEntity<InputStreamResource> downloadTemplate();
}
