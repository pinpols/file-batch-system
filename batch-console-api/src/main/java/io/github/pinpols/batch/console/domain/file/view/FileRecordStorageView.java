package io.github.pinpols.batch.console.domain.file.view;

/**
 * 文件内容上传、下载和详情查询共用的固定存储投影。
 *
 * <p>{@code metadataJson} 保持为原始 JSONB 对象，因为文件模板允许租户扩展元数据；其余列均为稳定的文件存储契约。
 */
public record FileRecordStorageView(
    Long id,
    String tenantId,
    String fileName,
    String mimeType,
    String storageType,
    String storagePath,
    String storageBucket,
    Object metadataJson) {}
