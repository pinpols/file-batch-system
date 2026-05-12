package com.example.batch.console.application.job;

import com.example.batch.console.web.query.JobDefinitionQueryRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;

/** 作业定义 Excel 模板与导出应用服务。 */
public interface ConsoleJobDefinitionExcelApplicationService {

  /** 按条件导出作业定义为 Excel。 */
  ResponseEntity<InputStreamResource> exportJobDefinitions(JobDefinitionQueryRequest request);

  /** 下载空白模板。 */
  ResponseEntity<InputStreamResource> downloadTemplate();
}
