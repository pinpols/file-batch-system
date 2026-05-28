package com.example.batch.orchestrator.controller;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.orchestrator.application.service.forensic.ForensicExportRequest;
import com.example.batch.orchestrator.application.service.forensic.ForensicExportResponse;
import com.example.batch.orchestrator.application.service.forensic.ForensicExportService;
import com.example.batch.orchestrator.domain.entity.ForensicExportLogEntity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-022 v0.1 Forensic 取证内部接口（仅供 console 通过 ConsoleOrchestratorProxyService HTTP 转发）。
 *
 * <p>动作：
 *
 * <ul>
 *   <li>POST /internal/forensic/export — 同步生成 bundle，返回 exportId / 路径 / sha256
 *   <li>GET /internal/forensic/export/{exportId}/download — 流式下载 zip
 * </ul>
 *
 * <p>不暴露给业务方；仅 ROLE_ADMIN 路径上来。主链路无影响（不在 trigger / claim / report 调用）。
 */
@RestController
@RequestMapping("/internal/forensic")
@RequiredArgsConstructor
public class ForensicExportController {

  private final ForensicExportService forensicExportService;

  @PostMapping("/export")
  public ForensicExportResponse export(@RequestBody ExportRequestBody body) {
    ForensicExportRequest request =
        ForensicExportRequest.builder()
            .tenantId(body.tenantId())
            .bizDateFrom(body.bizDateFrom())
            .bizDateTo(body.bizDateTo())
            .jobCodes(body.jobCodes())
            .exportFormat(body.exportFormat())
            .requestedBy(body.requestedBy())
            .traceId(body.traceId())
            .build();
    return forensicExportService.export(request);
  }

  @GetMapping("/export/{exportId}/download")
  public ResponseEntity<FileSystemResource> download(
      @PathVariable String exportId, @RequestParam("tenantId") String tenantId) throws IOException {
    ForensicExportLogEntity log = forensicExportService.findLog(tenantId, exportId);
    if (log == null) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.forensic.export_not_found");
    }
    if (!"COMPLETED".equals(log.status()) || log.storagePath() == null) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.forensic.export_not_ready");
    }
    Path file = Path.of(log.storagePath());
    if (!Files.exists(file)) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.forensic.export_file_missing");
    }
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + exportId + ".zip\"");
    headers.add("X-Forensic-Sha256", log.sha256() == null ? "" : log.sha256());
    return ResponseEntity.ok()
        .headers(headers)
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .contentLength(Files.size(file))
        .body(new FileSystemResource(file));
  }

  /** 内部 API request body（不暴露给业务方）。 */
  public record ExportRequestBody(
      String tenantId,
      LocalDate bizDateFrom,
      LocalDate bizDateTo,
      List<String> jobCodes,
      String exportFormat,
      String requestedBy,
      String traceId) {}
}
