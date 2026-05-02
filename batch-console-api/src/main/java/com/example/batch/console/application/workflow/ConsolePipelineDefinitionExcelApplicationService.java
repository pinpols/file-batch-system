package com.example.batch.console.application.workflow;

import com.example.batch.console.web.request.file.PipelineDefinitionExcelApplyRequest;
import com.example.batch.console.web.response.workflow.ConsolePipelineDefinitionExcelApplyResponse;
import com.example.batch.console.web.response.workflow.ConsolePipelineDefinitionExcelPreviewResponse;
import com.example.batch.console.web.response.workflow.ConsolePipelineDefinitionExcelUploadResponse;
import java.io.IOException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/** 流水线定义 Excel 导入导出应用服务。 */
public interface ConsolePipelineDefinitionExcelApplicationService {

  /** 导出流水线定义配置为 Excel（含 pipeline_definition 与 pipeline_step_definition 两个 Sheet）。 */
  ResponseEntity<InputStreamResource> exportPipelineDefinitions(
      String tenantId, String jobCode, String pipelineType, Boolean enabled);

  /** 下载空白模板。 */
  ResponseEntity<InputStreamResource> downloadTemplate();

  /** 上传 Excel 并返回 uploadToken。 */
  ConsolePipelineDefinitionExcelUploadResponse upload(MultipartFile file) throws IOException;

  /** 预览解析结果。 */
  ConsolePipelineDefinitionExcelPreviewResponse preview(String uploadToken);

  /** 下载带校验问题明细的预览 workbook。 */
  ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken);

  /** 确认导入并更新流水线定义配置。 */
  ConsolePipelineDefinitionExcelApplyResponse apply(
      String uploadToken, PipelineDefinitionExcelApplyRequest request);
}
