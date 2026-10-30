package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.ForensicExportLogEntity;
import io.github.pinpols.batch.orchestrator.domain.param.ForensicExportLogMarkCompletedParam;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** ADR-022 v0.1 forensic_export_log 表（V116）的 MyBatis mapper。 */
public interface ForensicExportLogMapper {

  enum ExportStatus {
    COMPLETED,
    FAILED
  }

  record CompletionParam(
      String tenantId,
      String exportId,
      String storagePath,
      Long fileSizeBytes,
      String sha256,
      String rowCountsJson,
      Instant completedAt,
      String targetStatus) {}

  record FailureParam(
      String tenantId,
      String exportId,
      String errorMessage,
      Instant completedAt,
      String targetStatus) {}

  int insert(ForensicExportLogEntity entity);

  ForensicExportLogEntity selectByExportId(
      @Param("tenantId") String tenantId, @Param("exportId") String exportId);

  /** Console 列表查询：按租户最近请求倒序。 */
  List<ForensicExportLogEntity> selectByTenant(
      @Param("tenantId") String tenantId, @Param("limit") int limit);

  /** 完成时回写 status=COMPLETED + storage_path + sha256 + row_counts + completed_at。 */
  default int markCompleted(ForensicExportLogMarkCompletedParam parameters) {
    String tenantId = parameters.tenantId();
    String exportId = parameters.exportId();
    String storagePath = parameters.storagePath();
    Long fileSizeBytes = parameters.fileSizeBytes();
    String sha256 = parameters.sha256();
    String rowCountsJson = parameters.rowCountsJson();
    Instant completedAt = parameters.completedAt();

    CompletionParam completion = new CompletionParam(
        tenantId,
        exportId,
        storagePath,
        fileSizeBytes,
        sha256,
        rowCountsJson,
        completedAt,
        ExportStatus.COMPLETED.name());
    return complete(completion);
  }

  int complete(CompletionParam param);

  /** 失败时回写 status=FAILED + error_message。 */
  default int markFailed(
      String tenantId, String exportId, String errorMessage, Instant completedAt) {
    FailureParam failure =
        new FailureParam(tenantId, exportId, errorMessage, completedAt, ExportStatus.FAILED.name());
    return fail(failure);
  }

  int fail(FailureParam param);
}
