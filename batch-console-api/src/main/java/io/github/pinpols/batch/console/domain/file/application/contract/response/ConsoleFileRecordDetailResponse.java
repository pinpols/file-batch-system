package io.github.pinpols.batch.console.domain.file.application.contract.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.PostgresqlJsonbTexts;
import io.github.pinpols.batch.common.utils.SensitiveEvidenceMasking;
import io.github.pinpols.batch.console.domain.file.view.FileRecordStorageView;
import java.util.List;
import java.util.Map;

/** 文件详情只读投影；存储路径和扩展元数据中的凭据在响应边界脱敏。 */
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
        SensitiveEvidenceMasking.maskText(row.storagePath()),
        row.storageBucket(),
        SensitiveEvidenceMasking.maskJsonValue(normalizeMetadata(row.metadataJson())));
  }

  private static Object normalizeMetadata(Object metadata) {
    if (EmptyChecks.isNull(metadata)
        || metadata instanceof Map<?, ?>
        || metadata instanceof List<?>
        || metadata instanceof String
        || metadata instanceof Number
        || metadata instanceof Boolean) {
      return metadata;
    }
    String json = PostgresqlJsonbTexts.tryExtract(metadata);
    if (EmptyChecks.isNull(json)) {
      throw new IllegalStateException("Unsupported file metadata representation");
    }
    return JsonUtils.fromJson(json, Object.class);
  }
}
