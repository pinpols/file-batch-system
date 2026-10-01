package io.github.pinpols.batch.console.domain.file.application;

import java.io.InputStream;

/** 文件系统 presign 下载用例：校验签名并打开对象流。 */
public interface ConsoleFilesystemPresignDownloadService {

  PresignedDownload open(String bucket, String key, Long expEpochSec, String signature);

  record PresignedDownload(String fileName, InputStream content) {}
}
