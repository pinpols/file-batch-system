package com.example.batch.console.web.excel;

import com.example.batch.console.application.ConsoleFileChannelExcelApplicationService;
import com.example.batch.console.web.query.FileChannelQueryRequest;
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

/** 文件通道（file_channel）配置的 Excel 导出与空白模板下载。回灌合并导入由 tenant-package Excel 流程承担。 */
@RestController
@Validated
@RequestMapping("/api/console/config/file-channels/excel")
@RequiredArgsConstructor
public class ConsoleFileChannelExcelController {

  private final ConsoleFileChannelExcelApplicationService applicationService;

  /**
   * 按查询条件导出当前租户可见的文件通道配置为 {@code .xlsx} 流。
   *
   * @param request 筛选条件（租户等，与列表查询一致）
   */
  @GetMapping("/export")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> export(
      @Valid @ModelAttribute FileChannelQueryRequest request) {
    return applicationService.exportFileChannels(request);
  }

  /** 下载空白模板。 */
  @GetMapping("/template")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> template() {
    return applicationService.downloadTemplate();
  }
}
