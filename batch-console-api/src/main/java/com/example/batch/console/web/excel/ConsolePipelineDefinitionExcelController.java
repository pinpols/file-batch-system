package com.example.batch.console.web.excel;

import com.example.batch.console.application.ConsolePipelineDefinitionExcelApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流水线定义（pipeline_definition + pipeline_step_definition）配置的 Excel 导出与空白模板下载。 回灌合并导入由 tenant-package
 * Excel 流程承担。
 */
@RestController
@Validated
@RequestMapping("/api/console/config/pipeline-definitions/excel")
@RequiredArgsConstructor
public class ConsolePipelineDefinitionExcelController {

  private final ConsolePipelineDefinitionExcelApplicationService applicationService;

  /** 按查询条件导出当前租户可见的流水线定义配置为 {@code .xlsx} 流。 */
  @GetMapping("/export")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> export(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "jobCode", required = false) String jobCode,
      @RequestParam(value = "pipelineType", required = false) String pipelineType,
      @RequestParam(value = "enabled", required = false) Boolean enabled) {
    return applicationService.exportPipelineDefinitions(tenantId, jobCode, pipelineType, enabled);
  }

  /** 下载空白模板。 */
  @GetMapping("/template")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> template() {
    return applicationService.downloadTemplate();
  }
}
