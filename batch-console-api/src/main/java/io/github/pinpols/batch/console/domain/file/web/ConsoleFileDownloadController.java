package io.github.pinpols.batch.console.domain.file.web;

import io.github.pinpols.batch.console.domain.file.application.ConsoleFileDownloadApplicationService;
import io.github.pinpols.batch.console.domain.file.application.FileDownloadArtifact;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 控制台文件下载 REST：流式返回对象存储文件（与写操作 Controller 同路径前缀，按 HTTP 方法区分）。 */
@RestController
@Validated
@RequestMapping("/api/console/files")
@RequiredArgsConstructor
public class ConsoleFileDownloadController {

  private final ConsoleFileDownloadApplicationService applicationService;

  /** 下载文件二进制流。 */
  @GetMapping("/{fileId}/download")
  public ResponseEntity<InputStreamResource> download(
      @PathVariable Long fileId,
      @RequestParam @NotNull String tenantId,
      @RequestParam(required = false) String approvalId) {
    return toResponse(applicationService.download(tenantId, fileId, approvalId));
  }

  /** 导出文件错误记录为 CSV。 */
  @GetMapping("/{fileId}/errors/export")
  public ResponseEntity<InputStreamResource> exportFileErrors(
      @PathVariable Long fileId,
      @RequestParam @NotNull String tenantId,
      @RequestParam(required = false) String errorStage) {
    return toResponse(applicationService.exportFileErrors(tenantId, fileId, errorStage));
  }

  private ResponseEntity<InputStreamResource> toResponse(FileDownloadArtifact artifact) {
    try {
      InputStreamResource resource = artifact.unknownLength()
          ? new InputStreamResource(artifact.content()) {
            @Override
            public long contentLength() {
              return -1;
            }
          }
          : new InputStreamResource(artifact.content());
      String disposition = ContentDisposition.attachment().filename(artifact.fileName()).build().toString();
      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
          .contentType(MediaType.parseMediaType(artifact.contentType()))
          .body(resource);
    } catch (RuntimeException failure) {
      try {
        artifact.content().close();
      } catch (IOException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      if (artifact.unknownLength()) {
        throw new IllegalStateException("failed to open download stream", failure);
      }
      throw failure;
    }
  }
}
