package com.example.batch.console.web.excel;

import com.example.batch.console.application.ConsoleJobDefinitionExcelApplicationService;
import com.example.batch.console.web.query.JobDefinitionQueryRequest;
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

/** 控制台作业定义 Excel 导出与空白模板下载。回灌合并导入由 tenant-package Excel 流程承担。 */
@RestController
@Validated
@RequestMapping("/api/console/config/job-definitions/excel")
@RequiredArgsConstructor
public class ConsoleJobDefinitionExcelController {

  private final ConsoleJobDefinitionExcelApplicationService applicationService;

  /** 导出作业定义 Excel 模板。 */
  @GetMapping("/export")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> export(
      @Valid @ModelAttribute JobDefinitionQueryRequest request) {
    return applicationService.exportJobDefinitions(request);
  }

  /** 下载空白模板。 */
  @GetMapping("/template")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> template() {
    return applicationService.downloadTemplate();
  }
}
