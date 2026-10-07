package io.github.pinpols.batch.orchestrator.application.contract.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/** BFS 结果版本的固定证据契约，只有文件 metadata 保留动态 JSON。 */
public record LineageEvidenceResponse(
    ResultVersion resultVersion,
    JobInstance jobInstance,
    List<PipelineInstance> pipelineInstances,
    List<FileRecord> fileRecords,
    List<DispatchRecord> dispatchRecords,
    LineageCoverage coverage) {

  public record LineageCoverage(
      String scope,
      Long resultVersionId,
      LineageSources sources,
      boolean jobInstanceFound,
      Long payloadFileId,
      boolean payloadFileResolved,
      int pipelineInstanceCount,
      int fileRecordCount,
      int dispatchRecordCount,
      List<String> knownGaps) {}

  public record LineageSources(
      String resultVersion,
      String jobInstance,
      String pipelineInstances,
      String fileRecords,
      String dispatchRecords) {}

  /** JobInstance 的固定证据字段；数据库投影保留既有 snake_case 键。 */
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record JobInstance(
      Long id,
      @JsonProperty("tenant_id") String tenantId,
      @JsonProperty("job_code") String jobCode,
      @JsonProperty("biz_date") LocalDate bizDate,
      @JsonProperty("instance_status") String instanceStatus,
      @JsonProperty("run_attempt") Integer runAttempt,
      @JsonProperty("trace_id") String traceId,
      @JsonProperty("related_file_id") Long relatedFileId,
      @JsonProperty("parent_instance_id") Long parentInstanceId,
      @JsonProperty("replay_session_id") Long replaySessionId,
      @JsonProperty("started_at") Instant startedAt,
      @JsonProperty("finished_at") Instant finishedAt,
      @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("updated_at") Instant updatedAt) {}

  /** PipelineInstance 的固定证据字段；数据库投影保留既有 snake_case 键。 */
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PipelineInstance(
      Long id,
      @JsonProperty("tenant_id") String tenantId,
      @JsonProperty("pipeline_definition_id") Long pipelineDefinitionId,
      @JsonProperty("job_code") String jobCode,
      @JsonProperty("pipeline_type") String pipelineType,
      @JsonProperty("file_id") Long fileId,
      @JsonProperty("related_job_instance_id") Long relatedJobInstanceId,
      @JsonProperty("current_stage") String currentStage,
      @JsonProperty("last_success_stage") String lastSuccessStage,
      @JsonProperty("run_status") String runStatus,
      @JsonProperty("trace_id") String traceId,
      @JsonProperty("started_at") Instant startedAt,
      @JsonProperty("finished_at") Instant finishedAt,
      @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("updated_at") Instant updatedAt) {}

  /** FileRecord 的固定证据字段；数据库投影保留既有 snake_case 键。 */
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record FileRecord(
      Long id,
      @JsonProperty("tenant_id") String tenantId,
      @JsonProperty("file_code") String fileCode,
      @JsonProperty("biz_type") String bizType,
      @JsonProperty("file_category") String fileCategory,
      @JsonProperty("file_name") String fileName,
      @JsonProperty("file_format_type") String fileFormatType,
      @JsonProperty("file_size_bytes") Long fileSizeBytes,
      @JsonProperty("checksum_type") String checksumType,
      @JsonProperty("checksum_value") String checksumValue,
      @JsonProperty("storage_type") String storageType,
      @JsonProperty("storage_bucket") String storageBucket,
      @JsonProperty("storage_path") String storagePath,
      @JsonProperty("file_status") String fileStatus,
      @JsonProperty("biz_date") LocalDate bizDate,
      @JsonProperty("trace_id") String traceId,
      @JsonProperty("metadata_json") Map<String, Object> metadataJson,
      @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("updated_at") Instant updatedAt) {}

  /** DispatchRecord 的固定证据字段；数据库投影保留既有 snake_case 键。 */
  @Builder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record DispatchRecord(
      Long id,
      @JsonProperty("tenant_id") String tenantId,
      @JsonProperty("file_id") Long fileId,
      @JsonProperty("pipeline_instance_id") Long pipelineInstanceId,
      @JsonProperty("channel_code") String channelCode,
      @JsonProperty("dispatch_target") String dispatchTarget,
      @JsonProperty("dispatch_status") String dispatchStatus,
      @JsonProperty("dispatch_attempt") Integer dispatchAttempt,
      @JsonProperty("receipt_code") String receiptCode,
      @JsonProperty("receipt_status") String receiptStatus,
      @JsonProperty("external_request_id") String externalRequestId,
      @JsonProperty("error_code") String errorCode,
      @JsonProperty("error_message") String errorMessage,
      @JsonProperty("dispatched_at") Instant dispatchedAt,
      @JsonProperty("ack_at") Instant ackAt,
      @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("updated_at") Instant updatedAt) {}

  /** 结果版本沿用既有 camelCase 键，未生成的版本字段保留 null。 */
  @Builder
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record ResultVersion(
      Long id,
      String tenantId,
      String businessKey,
      Integer versionNo,
      Long jobInstanceId,
      String status,
      Instant effectiveAt,
      Instant deactivatedAt,
      String payloadStorage,
      String payloadRef,
      Instant generatedAt,
      String generatedBy,
      String promotionPolicy,
      String dqGateStatus) {}
}
