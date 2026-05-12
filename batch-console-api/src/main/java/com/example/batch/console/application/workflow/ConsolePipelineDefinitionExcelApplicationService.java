package com.example.batch.console.application.workflow;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;

/** 流水线定义 Excel 模板与导出应用服务。 */
public interface ConsolePipelineDefinitionExcelApplicationService {

  /** 导出流水线定义配置为 Excel（含 pipeline_definition 与 pipeline_step_definition 两个 Sheet）。 */
  ResponseEntity<InputStreamResource> exportPipelineDefinitions(
      String tenantId, String jobCode, String pipelineType, Boolean enabled);

  /** 下载空白模板。 */
  ResponseEntity<InputStreamResource> downloadTemplate();
}
