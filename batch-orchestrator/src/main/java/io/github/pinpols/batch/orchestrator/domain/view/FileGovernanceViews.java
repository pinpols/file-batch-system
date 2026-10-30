package io.github.pinpols.batch.orchestrator.domain.view;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** 文件治理 Mapper 的固定列投影；动态 metadata 与缓存载荷仅在明确边界转换。 */
public final class FileGovernanceViews {

  private FileGovernanceViews() {}

  public record FileRecordRow(
      Long id, String fileStatus, String storageBucket, String storagePath) {}

  public record TemplateSecurityRow(
      Boolean previewMaskingEnabled,
      Boolean errorLineMaskingEnabled,
      Boolean logMaskingEnabled,
      Boolean contentEncryptionEnabled,
      String encryptionKeyRef,
      Boolean downloadRequiresApproval,
      String maskingRuleSet) {}

  public record DispatchRecordRow(Long id, Long pipelineInstanceId, String channelCode) {}

  public record ArchivedFileCleanupRow(
      Long id, String tenantId, String storagePath, String storageType, String fileStatus) {}

  public record OrphanUploadSessionRow(
      Long id,
      String tenantId,
      String fileName,
      String fileStatus,
      String storageBucket,
      String storagePath,
      Instant createdAt) {}

  public record ArrivalCandidateRow(
      Long id,
      String tenantId,
      String checksumType,
      String fileName,
      LocalDate bizDate,
      String fileStatus,
      Instant createdAt,
      Instant updatedAt,
      Object metadataJson,
      String fileGroupCode,
      String waitFileGroupMode,
      String requiredFileSet,
      String arrivalTimeoutAction,
      String arrivalState,
      String arrivalReason,
      String expectedArrivalTime,
      String latestTolerableTime,
      String triggerOnComplete) {}

  public record ArrivalGroupBacklogRow(Long waitingGroups, Long oldestWaitSeconds) {}

  public record ArrivalGroupSummaryRow(
      String tenantId,
      LocalDate bizDate,
      String fileGroupCode,
      String waitFileGroupMode,
      String requiredFileSet,
      String arrivalTimeoutAction,
      String arrivalState,
      String expectedArrivalTime,
      String latestTolerableTime,
      Long arrivedCount,
      Long triggeredCount,
      Long timeoutCount,
      Long waitingCount,
      Instant lastUpdatedAt) {}

  public record ArrivalGroupFileRow(
      Long id,
      String tenantId,
      String fileName,
      String fileStatus,
      LocalDate bizDate,
      Object metadataJson,
      String fileGroupCode,
      String arrivalState,
      String arrivalTimeoutAction,
      String allowEmptyRun,
      String allowSkipBizDate,
      String requiredFileSet,
      String latestTolerableTime) {}

  public record ArrivalDelaySampleRow(
      String tenantId,
      Long id,
      String fileName,
      Instant createdAt,
      String expectedArrivalTime,
      Long arrivalDelaySeconds) {
    public Map<String, Object> toCacheValues() {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("tenant_id", tenantId);
      values.put("id", id);
      values.put("file_name", fileName);
      values.put("created_at", createdAt);
      values.put("expected_arrival_time", expectedArrivalTime);
      values.put("arrival_delay_seconds", arrivalDelaySeconds);
      return values;
    }
  }

  public record ProcessingDelaySampleRow(
      String tenantId,
      Long id,
      String jobCode,
      String pipelineType,
      Long relatedJobInstanceId,
      Instant startedAt,
      Long processingDelaySeconds) {
    public Map<String, Object> toCacheValues() {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("tenant_id", tenantId);
      values.put("id", id);
      values.put("job_code", jobCode);
      values.put("pipeline_type", pipelineType);
      values.put("related_job_instance_id", relatedJobInstanceId);
      values.put("started_at", startedAt);
      values.put("processing_delay_seconds", processingDelaySeconds);
      return values;
    }
  }
}
