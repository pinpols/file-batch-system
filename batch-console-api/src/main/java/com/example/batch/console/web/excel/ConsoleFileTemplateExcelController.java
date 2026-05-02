package com.example.batch.console.web.excel;

import com.example.batch.console.application.file.ConsoleFileTemplateExcelApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.query.FileTemplateQueryRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件模板 Excel 导出 REST:仅保留 export / template。
 *
 * <p>历史 upload / preview / preview-workbook / apply 4 个端点已于 2026-05-01 物删 (文件模板由建租户时从 {@code
 * default} 模板自动初始化,后续调整通过页面单条维护)。
 */
@SuppressWarnings("unused")
@RestController
@Validated
@RequestMapping("/api/console/config/file-templates/excel")
@RequiredArgsConstructor
public class ConsoleFileTemplateExcelController {

  private final ConsoleFileTemplateExcelApplicationService applicationService;
  private final ConsoleResponseFactory responseFactory;

  /** 导出文件模板 Excel。 */
  @GetMapping("/export")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> export(
      @Valid @ModelAttribute FileTemplateQueryRequest request) {
    return applicationService.exportFileTemplates(request);
  }

  /** 下载空白模板。 */
  @GetMapping("/template")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> template() {
    return applicationService.downloadTemplate();
  }
}
