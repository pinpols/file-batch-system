package com.example.batch.console.application.job;

import com.example.batch.console.web.query.JobDefinitionQueryRequest;
import com.example.batch.console.web.request.job.JobDefinitionExcelApplyRequest;
import com.example.batch.console.web.response.job.ConsoleJobDefinitionExcelApplyResponse;
import com.example.batch.console.web.response.job.ConsoleJobDefinitionExcelPreviewResponse;
import com.example.batch.console.web.response.job.ConsoleJobDefinitionExcelUploadResponse;
import java.io.IOException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/** 作业定义 Excel 导入导出应用服务（与控制台批量维护 job_definition 配套）。 */
public interface ConsoleJobDefinitionExcelApplicationService {

  /** 按条件导出作业定义为 Excel。 */
  ResponseEntity<InputStreamResource> exportJobDefinitions(JobDefinitionQueryRequest request);

  /** 下载空白模板。 */
  ResponseEntity<InputStreamResource> downloadTemplate();

  /** 上传 Excel 并返回 uploadToken。 */
  ConsoleJobDefinitionExcelUploadResponse upload(MultipartFile file) throws IOException;

  /** 预览解析与校验结果。 */
  ConsoleJobDefinitionExcelPreviewResponse preview(String uploadToken);

  /** 下载带校验问题明细的预览 workbook。 */
  ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken);

  /** 确认导入并批量更新作业定义。 */
  ConsoleJobDefinitionExcelApplyResponse apply(
      String uploadToken, JobDefinitionExcelApplyRequest request);
}
