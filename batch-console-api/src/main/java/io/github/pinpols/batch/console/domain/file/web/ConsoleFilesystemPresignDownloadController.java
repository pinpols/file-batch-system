package io.github.pinpols.batch.console.domain.file.web;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.config.FilesystemStorageProperties;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.storage.FilesystemPresignTokens;
import io.github.pinpols.batch.common.storage.ObjectNotFoundException;
import io.github.pinpols.batch.common.utils.Texts;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 文件系统后端 presign 代下端点（Phase 2 §4①）。
 *
 * <p>S3 后端 presign 是真签名 URL、对象存储直发，无需此端点。仅当 {@code batch.storage.backend=filesystem} 时才装配此
 * Controller。
 *
 * <p>授权模型：URL 自身的 HMAC 令牌即授权（{@link FilesystemPresignTokens#verify}），无登录态——故端点路径在 {@code
 * ConsoleSecurityConfiguration} 的 {@code permitAll} 白名单中。令牌验证失败 → 401；URL 校验失败 → 400；对象不存在 → 404。
 *
 * <p>设计取舍：跟现有 {@code DefaultConsoleFileDownloadApplicationService}（走 ConsoleAuth + 模板审批门控）分工——
 * 后者面向「console 登录用户主动点下载」，本端点面向「presign URL 单击下载」（如 governance 对账给的 URL）。
 */
@Slf4j
@RestController
@Validated
@RequestMapping("/api/console/files")
@ConditionalOnProperty(name = "batch.storage.backend", havingValue = "filesystem")
@RequiredArgsConstructor
public class ConsoleFilesystemPresignDownloadController {

  private final BatchObjectStore objectStore;
  private final FilesystemStorageProperties filesystemProperties;
  private final BatchSecurityProperties securityProperties;

  /** FS presign URL 单击下载。 */
  @GetMapping("/fs-download")
  public ResponseEntity<StreamingResponseBody> download(
      @RequestParam("b") @NotBlank String bucket,
      @RequestParam("k") @NotBlank String key,
      @RequestParam("e") @NotNull Long expEpochSec,
      @RequestParam("s") @NotBlank String signature) {
    // 1) 拒绝穿越 key（即便 verify 通过，恶意签发方也不能让端点穿越 root）
    if (key.contains("..") || key.startsWith("/")) {
      throw BizException.of(ResultCode.BUSINESS_ERROR, "error.file.fs_presign_key_invalid");
    }
    // 2) HMAC + 过期校验
    String secret =
        Texts.hasText(filesystemProperties.getPresignSecret())
            ? filesystemProperties.getPresignSecret()
            : securityProperties.getInternalSecret();
    if (!FilesystemPresignTokens.verify(bucket, key, expEpochSec, signature, secret)) {
      log.warn(
          "fs presign download rejected: bucket={} key={} exp={} (invalid signature or expired)",
          bucket,
          key,
          expEpochSec);
      throw BizException.of(ResultCode.UNAUTHORIZED, "error.file.fs_presign_invalid");
    }
    // 3) 走 BatchObjectStore.get（加密装饰层若启用会透明解密）
    final String fileName = key.substring(key.lastIndexOf('/') + 1);
    StreamingResponseBody body =
        (OutputStream out) -> {
          try (InputStream in = openStream(bucket, key)) {
            in.transferTo(out);
          } catch (ObjectNotFoundException notFound) {
            throw new IOException("fs object not found: " + key, notFound);
          }
        };
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(fileName).build().toString())
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(body);
  }

  private InputStream openStream(String bucket, String key) {
    try {
      return objectStore.get(bucket, key);
    } catch (ObjectNotFoundException notFound) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.file.content_not_found");
    }
  }
}
