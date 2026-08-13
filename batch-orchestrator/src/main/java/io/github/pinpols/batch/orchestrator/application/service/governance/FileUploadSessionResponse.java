package io.github.pinpols.batch.orchestrator.application.service.governance;

/** 应用托管上传会话的固定响应，供 Console 再包装为公开文件上传契约。 */
public record FileUploadSessionResponse(
    Long fileId,
    String status,
    String uploadMode,
    String uploadMethod,
    String contentField,
    String uploadUrl,
    String storageBucket,
    String storagePath,
    String fileName) {}
