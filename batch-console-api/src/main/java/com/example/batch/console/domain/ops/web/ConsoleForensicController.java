package com.example.batch.console.domain.ops.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.ops.application.ConsoleOrchestratorProxyService;
import com.example.batch.console.domain.ops.web.request.ForensicExportRequest;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-022 v0.1 Forensic 取证控制台入口。
 *
 * <p>v0.1 仅支持同步生成 bundle（小 bizDate 范围秒级）。大范围异步、OSS 长保留、*_history 影子表均推迟到 v0.2+。
 *
 * <p>权限：仅 ROLE_ADMIN — 取证是高风险数据导出。
 */
@RestController
@Validated
@RequestMapping("/api/console/forensic")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleForensicController {

  private final ConsoleOrchestratorProxyService orchestratorProxyService;
  private final ConsoleResponseFactory responseFactory;

  @PostMapping("/export")
  public CommonResponse<Map<String, Object>> requestExport(
      @Valid @RequestBody ForensicExportRequest request) {
    Map<String, Object> result =
        orchestratorProxyService.requestForensicExport(
            request.getTenantId(),
            request.getBizDateFrom(),
            request.getBizDateTo(),
            request.getJobCodes(),
            request.getExportFormat() == null ? "BUNDLE" : request.getExportFormat(),
            request.getRequestedBy());
    return responseFactory.success(result);
  }

  @GetMapping("/export/{exportId}/download")
  public ResponseEntity<byte[]> download(
      @PathVariable String exportId, @RequestParam("tenantId") String tenantId) {
    byte[] bytes = orchestratorProxyService.downloadForensicExport(tenantId, exportId);
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + exportId + ".zip\"");
    return ResponseEntity.ok()
        .headers(headers)
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(bytes);
  }
}
