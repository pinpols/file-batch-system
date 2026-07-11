package io.github.pinpols.batch.console.domain.file.web.response;

import java.util.Map;

/**
 * 托管上传会话响应（{@code POST /api/console/files/presign-upload}）。
 *
 * <p>编排内部 {@code /internal/files/presign-upload}（{@code FileGovernanceService.createUploadSession}）
 * 用 {@code LinkedHashMap} 逐键显式 {@code put} 构建 9 个 camelCase 固定字段：{@code fileId / status /
 * uploadMode / uploadMethod / contentField / uploadUrl / storageBucket / storagePath / fileName}，
 * 均恒非空。console 仅在 controller 边界经 {@link #from(Map)} 透传转换，编排 service 与 proxy 返回类型不动，键一字不差。
 */
public record ConsoleFilePresignUploadResponse(
    Long fileId,
    String status,
    String uploadMode,
    String uploadMethod,
    String contentField,
    String uploadUrl,
    String storageBucket,
    String storagePath,
    String fileName) {

  public static ConsoleFilePresignUploadResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleFilePresignUploadResponse(
        FileMapResponseFieldReader.longValue(row, "fileId"),
        FileMapResponseFieldReader.stringValue(row, "status"),
        FileMapResponseFieldReader.stringValue(row, "uploadMode"),
        FileMapResponseFieldReader.stringValue(row, "uploadMethod"),
        FileMapResponseFieldReader.stringValue(row, "contentField"),
        FileMapResponseFieldReader.stringValue(row, "uploadUrl"),
        FileMapResponseFieldReader.stringValue(row, "storageBucket"),
        FileMapResponseFieldReader.stringValue(row, "storagePath"),
        FileMapResponseFieldReader.stringValue(row, "fileName"));
  }
}
