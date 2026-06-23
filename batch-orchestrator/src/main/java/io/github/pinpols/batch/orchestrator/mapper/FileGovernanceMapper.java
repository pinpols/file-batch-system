package io.github.pinpols.batch.orchestrator.mapper;

import java.util.List;
import java.util.Map;

public interface FileGovernanceMapper {

  Map<String, Object> selectFileRecord(Map<String, Object> params);

  Map<String, Object> selectFileTemplateSecurity(Map<String, Object> params);

  Long countActivePipelineInstances(Map<String, Object> params);

  Long countPendingDispatchRecords(Map<String, Object> params);

  Map<String, Object> selectLatestDispatchRecord(Map<String, Object> params);

  Long selectRelatedJobInstanceId(Map<String, Object> params);

  int resetDispatchRecordForRedispatch(Map<String, Object> params);

  List<Map<String, Object>> selectArchivedFilesForCleanup(Map<String, Object> params);

  List<Map<String, Object>> selectOrphanUploadSessions(Map<String, Object> params);

  List<Map<String, Object>> selectArrivalGovernanceCandidates(Map<String, Object> params);

  List<Map<String, Object>> selectArrivalGroupSummaries(Map<String, Object> params);

  List<Map<String, Object>> selectArrivalGroupFiles(Map<String, Object> params);

  Long countArrivalDelayViolations(Map<String, Object> params);

  Long selectMaxArrivalDelaySeconds(Map<String, Object> params);

  List<Map<String, Object>> selectArrivalDelaySamples(Map<String, Object> params);

  Long countProcessingDelayViolations(Map<String, Object> params);

  Long selectMaxProcessingDelaySeconds(Map<String, Object> params);

  List<Map<String, Object>> selectProcessingDelaySamples(Map<String, Object> params);

  Long countFileRecordByStoragePath(Map<String, Object> params);

  int insertReconciledFileRecord(Map<String, Object> params);

  int updateFileStatus(Map<String, Object> params);

  int updateFileMetadata(Map<String, Object> params);

  int markFileArrivalConfirmed(Map<String, Object> params);

  int insertFileAuditLog(Map<String, Object> params);

  int markStaleRunningPipelineInstancesFailed(Map<String, Object> params);

  int markRunningPipelineStepsFailedForInstances(Map<String, Object> params);
}
