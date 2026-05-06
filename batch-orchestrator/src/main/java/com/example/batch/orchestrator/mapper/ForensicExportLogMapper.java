package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.ForensicExportLogEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** ADR-022 v0.1 forensic_export_log 表（V116）的 MyBatis mapper。 */
public interface ForensicExportLogMapper {

  int insert(ForensicExportLogEntity entity);

  ForensicExportLogEntity selectByExportId(
      @Param("tenantId") String tenantId, @Param("exportId") String exportId);

  /** Console 列表查询：按租户最近请求倒序。 */
  List<ForensicExportLogEntity> selectByTenant(
      @Param("tenantId") String tenantId, @Param("limit") int limit);

  /** 完成时回写 status=COMPLETED + storage_path + sha256 + row_counts + completed_at。 */
  int markCompleted(
      @Param("tenantId") String tenantId,
      @Param("exportId") String exportId,
      @Param("storagePath") String storagePath,
      @Param("fileSizeBytes") Long fileSizeBytes,
      @Param("sha256") String sha256,
      @Param("rowCountsJson") String rowCountsJson,
      @Param("completedAt") Instant completedAt);

  /** 失败时回写 status=FAILED + error_message。 */
  int markFailed(
      @Param("tenantId") String tenantId,
      @Param("exportId") String exportId,
      @Param("errorMessage") String errorMessage,
      @Param("completedAt") Instant completedAt);
}
