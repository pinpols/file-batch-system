package com.example.batch.console.application;

import com.example.batch.console.web.query.WorkflowDefinitionQueryRequest;
import com.example.batch.console.web.request.workflow.WorkflowExcelApplyRequest;
import com.example.batch.console.web.response.workflow.ConsoleWorkflowExcelApplyResponse;
import com.example.batch.console.web.response.workflow.ConsoleWorkflowExcelPreviewResponse;
import com.example.batch.console.web.response.workflow.ConsoleWorkflowExcelUploadResponse;
import java.io.IOException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/** 工作流定义 Excel 导入导出应用服务：导出、上传解析、预览与确认落库。 */
public interface ConsoleWorkflowExcelApplicationService {

  /** 按筛选条件导出工作流定义为 Excel 流。 */
  ResponseEntity<InputStreamResource> exportWorkflowExcel(WorkflowDefinitionQueryRequest request);

  /** 下载空白模板。 */
  ResponseEntity<InputStreamResource> downloadTemplate();

  /** 上传 Excel 并生成临时 uploadToken。 */
  ConsoleWorkflowExcelUploadResponse upload(MultipartFile file) throws IOException;

  /** 根据 uploadToken 预览解析结果与校验问题。 */
  ConsoleWorkflowExcelPreviewResponse preview(String uploadToken);

  /** 下载带校验问题明细的预览 workbook。 */
  ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken);

  /** 将预览通过的数据写入数据库。 */
  ConsoleWorkflowExcelApplyResponse apply(String uploadToken, WorkflowExcelApplyRequest request);
}
