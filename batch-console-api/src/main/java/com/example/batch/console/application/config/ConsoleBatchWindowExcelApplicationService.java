package com.example.batch.console.application.config;

import com.example.batch.console.web.request.excel.ExcelApplyRequest;
import com.example.batch.console.web.response.excel.ExcelApplyResponse;
import com.example.batch.console.web.response.excel.ExcelPreviewResponse;
import com.example.batch.console.web.response.excel.ExcelUploadResponse;
import com.example.batch.console.web.response.file.ConsoleBatchWindowResponse;
import java.io.IOException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/** 批处理窗口 Excel 导入导出应用服务。 */
public interface ConsoleBatchWindowExcelApplicationService {

  /** 导出批处理窗口配置为 Excel。 */
  ResponseEntity<InputStreamResource> exportBatchWindows(String tenantId);

  /** 下载空白模板。 */
  ResponseEntity<InputStreamResource> downloadTemplate();

  /** 上传 Excel 并返回 uploadToken。 */
  ExcelUploadResponse upload(MultipartFile file) throws IOException;

  /** 预览解析结果。 */
  ExcelPreviewResponse<ConsoleBatchWindowResponse> preview(String uploadToken);

  /** 下载带校验问题明细的预览 workbook。 */
  ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken);

  /** 确认导入并更新批处理窗口配置。 */
  ExcelApplyResponse apply(String uploadToken, ExcelApplyRequest request);
}
