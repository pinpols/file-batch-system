package com.example.batch.console.application.file;

import com.example.batch.console.web.query.FileChannelQueryRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;

/** 文件通道 Excel 模板与导出应用服务。 */
public interface ConsoleFileChannelExcelApplicationService {

  /** 导出文件通道配置为 Excel。 */
  ResponseEntity<InputStreamResource> exportFileChannels(FileChannelQueryRequest request);

  /** 下载空白模板。 */
  ResponseEntity<InputStreamResource> downloadTemplate();
}
