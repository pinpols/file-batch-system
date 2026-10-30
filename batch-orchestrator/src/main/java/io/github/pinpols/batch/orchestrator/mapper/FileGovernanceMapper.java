package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.param.FileGovernanceAuditInsertParam;
import io.github.pinpols.batch.orchestrator.domain.param.FileGovernanceReconcileInsertParam;
import io.github.pinpols.batch.orchestrator.domain.param.FileGovernanceStalePipelineParam;
import io.github.pinpols.batch.orchestrator.domain.param.FileGovernanceStaleStepParam;
import io.github.pinpols.batch.orchestrator.domain.param.FileGovernanceStatusUpdateParam;
import io.github.pinpols.batch.orchestrator.domain.query.FileGovernanceActivePipelineQuery;
import io.github.pinpols.batch.orchestrator.domain.query.FileGovernanceOrphanUploadQuery;
import io.github.pinpols.batch.orchestrator.domain.query.FileGovernancePendingDispatchQuery;
import io.github.pinpols.batch.orchestrator.domain.query.FileGovernanceProcessingDelayQuery;
import io.github.pinpols.batch.orchestrator.domain.view.FileGovernanceViews;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface FileGovernanceMapper {

  FileGovernanceViews.FileRecordRow selectFileRecord(
      @Param("tenantId") String tenantId, @Param("fileId") Long fileId);

  FileGovernanceViews.TemplateSecurityRow selectFileTemplateSecurity(
      @Param("tenantId") String tenantId, @Param("fileId") Long fileId);

  Long countActivePipelineInstances(FileGovernanceActivePipelineQuery params);

  Long countPendingDispatchRecords(FileGovernancePendingDispatchQuery params);

  FileGovernanceViews.DispatchRecordRow selectLatestDispatchRecord(
      @Param("tenantId") String tenantId,
      @Param("fileId") Long fileId,
      @Param("channelCode") String channelCode);

  Long selectRelatedJobInstanceId(@Param("pipelineInstanceId") Long pipelineInstanceId);

  int resetDispatchRecordForRedispatch(
      @Param("tenantId") String tenantId,
      @Param("dispatchRecordId") Long dispatchRecordId,
      @Param("dispatchFailedStatus") String dispatchFailedStatus,
      @Param("receiptNoneStatus") String receiptNoneStatus);

  List<FileGovernanceViews.ArchivedFileCleanupRow> selectArchivedFilesForCleanup(
      @Param("cutoff") Instant cutoff,
      @Param("limit") Integer limit,
      @Param("archivedStatus") String archivedStatus);

  List<FileGovernanceViews.OrphanUploadSessionRow> selectOrphanUploadSessions(
      FileGovernanceOrphanUploadQuery params);

  List<FileGovernanceViews.ArrivalCandidateRow> selectArrivalGovernanceCandidates(
      @Param("limit") Integer limit);

  FileGovernanceViews.ArrivalGroupBacklogRow selectArrivalGroupBacklogMetrics();

  List<FileGovernanceViews.ArrivalGroupSummaryRow> selectArrivalGroupSummaries(
      @Param("tenantId") String tenantId,
      @Param("fileGroupCode") String fileGroupCode,
      @Param("arrivalState") String arrivalState);

  List<FileGovernanceViews.ArrivalGroupFileRow> selectArrivalGroupFiles(
      @Param("tenantId") String tenantId,
      @Param("fileGroupCode") String fileGroupCode,
      @Param("bizDate") String bizDate);

  Long countArrivalDelayViolations(
      @Param("tenantId") String tenantId, @Param("thresholdSeconds") Long thresholdSeconds);

  Long selectMaxArrivalDelaySeconds(@Param("tenantId") String tenantId);

  List<FileGovernanceViews.ArrivalDelaySampleRow> selectArrivalDelaySamples(
      @Param("tenantId") String tenantId,
      @Param("thresholdSeconds") Long thresholdSeconds,
      @Param("limit") Integer limit);

  Long countProcessingDelayViolations(
      @Param("tenantId") String tenantId,
      @Param("thresholdSeconds") Long thresholdSeconds,
      @Param("maxAgeSeconds") Long maxAgeSeconds,
      @Param("runningStatus") String runningStatus);

  Long selectMaxProcessingDelaySeconds(
      @Param("tenantId") String tenantId,
      @Param("maxAgeSeconds") Long maxAgeSeconds,
      @Param("runningStatus") String runningStatus);

  List<FileGovernanceViews.ProcessingDelaySampleRow> selectProcessingDelaySamples(
      FileGovernanceProcessingDelayQuery params);

  Long countFileRecordByStoragePath(
      @Param("tenantId") String tenantId,
      @Param("storageBucket") String storageBucket,
      @Param("storagePath") String storagePath);

  int insertReconciledFileRecord(FileGovernanceReconcileInsertParam params);

  int updateFileStatus(FileGovernanceStatusUpdateParam params);

  int updateFileMetadata(
      @Param("tenantId") String tenantId,
      @Param("fileId") Long fileId,
      @Param("metadataJson") String metadataJson);

  int updateArrivalGroupMetadataIfNonTerminal(
      @Param("tenantId") String tenantId,
      @Param("fileId") Long fileId,
      @Param("metadataJson") String metadataJson);

  int markFileArrivalConfirmed(
      @Param("tenantId") String tenantId,
      @Param("fileId") Long fileId,
      @Param("fileSizeBytes") Long fileSizeBytes,
      @Param("metadataJson") String metadataJson);

  int insertFileAuditLog(FileGovernanceAuditInsertParam params);

  List<Long> markStaleRunningPipelineInstancesFailed(FileGovernanceStalePipelineParam params);

  int markRunningPipelineStepsFailedForInstances(FileGovernanceStaleStepParam params);
}
