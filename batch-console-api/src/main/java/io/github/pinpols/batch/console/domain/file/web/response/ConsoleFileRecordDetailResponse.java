package io.github.pinpols.batch.console.domain.file.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.pinpols.batch.console.domain.file.mapper.view.FileRecordStorageView;

/** 文件详情的固定存储投影；{@code metadataJson} 保留原始 JSONB 扩展数据。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsoleFileRecordDetailResponse(
    Long id,
    @JsonProperty("tenant_id") String tenantId,
    @JsonProperty("file_name") String fileName,
    @JsonProperty("mime_type") String mimeType,
    @JsonProperty("storage_type") String storageType,
    @JsonProperty("storage_path") String storagePath,
    @JsonProperty("storage_bucket") String storageBucket,
    @JsonProperty("metadata_json") Object metadataJson) {

  public static ConsoleFileRecordDetailResponse from(FileRecordStorageView row) {
    return new ConsoleFileRecordDetailResponse(
        row.id(),
        row.tenantId(),
        row.fileName(),
        row.mimeType(),
        row.storageType(),
        row.storagePath(),
        row.storageBucket(),
        row.metadataJson());
  }
}
