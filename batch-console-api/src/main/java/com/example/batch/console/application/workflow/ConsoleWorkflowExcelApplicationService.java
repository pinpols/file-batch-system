package com.example.batch.console.application.workflow;

import com.example.batch.console.web.query.WorkflowDefinitionQueryRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;

/** 工作流定义 Excel 模板与导出应用服务。 */
public interface ConsoleWorkflowExcelApplicationService {

  /** 按筛选条件导出工作流定义为 Excel 流。 */
  ResponseEntity<InputStreamResource> exportWorkflowExcel(WorkflowDefinitionQueryRequest request);

  /** 下载空白模板。 */
  ResponseEntity<InputStreamResource> downloadTemplate();
}
