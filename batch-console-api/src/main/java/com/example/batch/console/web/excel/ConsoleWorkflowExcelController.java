package com.example.batch.console.web.excel;

import com.example.batch.console.application.ConsoleWorkflowExcelApplicationService;
import com.example.batch.console.web.query.WorkflowDefinitionQueryRequest;
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

/** 工作流定义 Excel 导出与空白模板下载。回灌合并导入由 tenant-package Excel 流程承担。 */
@RestController
@Validated
@RequestMapping("/api/console/config/workflows/excel")
@RequiredArgsConstructor
public class ConsoleWorkflowExcelController {

  private final ConsoleWorkflowExcelApplicationService applicationService;

  /** 导出工作流 Excel。 */
  @GetMapping("/export")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> export(
      @Valid @ModelAttribute WorkflowDefinitionQueryRequest request) {
    return applicationService.exportWorkflowExcel(request);
  }

  /** 下载空白模板。 */
  @GetMapping("/template")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> template() {
    return applicationService.downloadTemplate();
  }
}
