package com.example.batch.console.application.file;

import com.example.batch.console.web.query.FileTemplateQueryRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;

/** 文件模板 Excel 模板与导出应用服务。 */
public interface ConsoleFileTemplateExcelApplicationService {

  /** 导出文件模板配置为 Excel。 */
  ResponseEntity<InputStreamResource> exportFileTemplates(FileTemplateQueryRequest request);

  /** 下载空白模板。 */
  ResponseEntity<InputStreamResource> downloadTemplate();
}
