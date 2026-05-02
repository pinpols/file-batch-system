package com.example.batch.console.application;

import com.example.batch.console.web.request.ExcelApplyRequest;
import com.example.batch.console.web.response.ExcelApplyResponse;
import com.example.batch.console.web.response.ExcelPreviewResponse;
import com.example.batch.console.web.response.ExcelUploadResponse;
import com.example.batch.console.web.response.config.ConsoleResourceQueueResponse;
import java.io.IOException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/** 资源队列 Excel 导入导出应用服务。 */
public interface ConsoleResourceQueueExcelApplicationService {

  /** 导出资源队列配置为 Excel。 */
  ResponseEntity<InputStreamResource> exportResourceQueues(
      String tenantId, String queueCode, String queueType, Boolean enabled);

  /** 下载空白模板。 */
  ResponseEntity<InputStreamResource> downloadTemplate();

  /** 上传 Excel 并返回 uploadToken。 */
  ExcelUploadResponse upload(MultipartFile file) throws IOException;

  /** 预览解析结果。 */
  ExcelPreviewResponse<ConsoleResourceQueueResponse> preview(String uploadToken);

  /** 下载带校验问题明细的预览 workbook。 */
  ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken);

  /** 确认导入并更新资源队列配置。 */
  ExcelApplyResponse apply(String uploadToken, ExcelApplyRequest request);
}
