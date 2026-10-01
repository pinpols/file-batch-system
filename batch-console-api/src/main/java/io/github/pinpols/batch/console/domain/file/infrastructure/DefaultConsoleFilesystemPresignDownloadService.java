package io.github.pinpols.batch.console.domain.file.infrastructure;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.config.FilesystemStorageProperties;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.storage.FilesystemPresignTokens;
import io.github.pinpols.batch.common.storage.ObjectNotFoundException;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.domain.file.application.ConsoleFilesystemPresignDownloadService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 文件系统 presign 下载用例的基础设施实现。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleFilesystemPresignDownloadService
    implements ConsoleFilesystemPresignDownloadService {

  private final BatchObjectStore objectStore;
  private final FilesystemStorageProperties filesystemProperties;
  private final BatchSecurityProperties securityProperties;

  @Override
  public PresignedDownload open(String bucket, String key, Long expEpochSec, String signature) {
    if (key.contains("..") || key.startsWith("/")) {
      throw BizException.of(ResultCode.BUSINESS_ERROR, "error.file.fs_presign_key_invalid");
    }
    String secret = Texts.hasText(filesystemProperties.getPresignSecret())
        ? filesystemProperties.getPresignSecret()
        : securityProperties.getInternalSecret();
    if (!FilesystemPresignTokens.verify(bucket, key, expEpochSec, signature, secret)) {
      throw BizException.of(ResultCode.UNAUTHORIZED, "error.file.fs_presign_invalid");
    }
    try {
      return new PresignedDownload(
          key.substring(key.lastIndexOf('/') + 1), objectStore.get(bucket, key));
    } catch (ObjectNotFoundException exception) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.file.content_not_found");
    }
  }
}
