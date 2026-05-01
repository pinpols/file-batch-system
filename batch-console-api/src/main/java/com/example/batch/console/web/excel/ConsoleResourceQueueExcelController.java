package com.example.batch.console.web.excel;

import com.example.batch.console.application.ConsoleResourceQueueExcelApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
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
 * 资源队列(resource_queue)配置的 Excel 导出 REST:仅保留 export / template。
 *
 * <p>历史 upload / preview / preview-workbook / apply 4 个端点已于 2026-05-01 物删 (资源队列由建租户时从 {@code
 * default} 模板自动初始化,后续调整通过页面单条维护)。
 */
@SuppressWarnings("unused")
@RestController
@Validated
@RequestMapping("/api/console/config/resource-queues/excel")
@RequiredArgsConstructor
public class ConsoleResourceQueueExcelController {

  private final ConsoleResourceQueueExcelApplicationService applicationService;
  private final ConsoleResponseFactory responseFactory;

  /** 按查询条件导出当前租户可见的资源队列配置为 {@code .xlsx} 流。 */
  @GetMapping("/export")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> export(
      @RequestParam(required = false) String tenantId,
      @RequestParam(required = false) String queueCode,
      @RequestParam(required = false) String queueType,
      @RequestParam(required = false) Boolean enabled) {
    return applicationService.exportResourceQueues(tenantId, queueCode, queueType, enabled);
  }

  /** 下载空白模板。 */
  @GetMapping("/template")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_AUDITOR')")
  public ResponseEntity<InputStreamResource> template() {
    return applicationService.downloadTemplate();
  }
}
