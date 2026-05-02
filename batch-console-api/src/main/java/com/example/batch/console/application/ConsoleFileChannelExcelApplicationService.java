package com.example.batch.console.application;

import com.example.batch.console.web.query.FileChannelQueryRequest;
import com.example.batch.console.web.request.ExcelApplyRequest;
import com.example.batch.console.web.response.ExcelApplyResponse;
import com.example.batch.console.web.response.ExcelPreviewResponse;
import com.example.batch.console.web.response.ExcelUploadResponse;
import com.example.batch.console.web.response.file.ConsoleFileChannelResponse;
import java.io.IOException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/** 文件通道 Excel 导入导出应用服务。 */
public interface ConsoleFileChannelExcelApplicationService {

  /** 导出文件通道配置为 Excel。 */
  ResponseEntity<InputStreamResource> exportFileChannels(FileChannelQueryRequest request);

  /** 下载空白模板。 */
  ResponseEntity<InputStreamResource> downloadTemplate();

  /** 上传 Excel 并返回 uploadToken。 */
  ExcelUploadResponse upload(MultipartFile file) throws IOException;

  /** 预览解析结果。 */
  ExcelPreviewResponse<ConsoleFileChannelResponse> preview(String uploadToken);

  /** 下载带校验问题明细的预览 workbook。 */
  ResponseEntity<InputStreamResource> downloadPreviewWorkbook(String uploadToken);

  /** 确认导入并更新通道配置。 */
  ExcelApplyResponse apply(String uploadToken, ExcelApplyRequest request);
}
