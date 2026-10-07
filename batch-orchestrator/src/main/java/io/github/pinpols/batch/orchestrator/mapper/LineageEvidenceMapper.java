package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.DispatchRecord;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.FileRecord;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.JobInstance;
import io.github.pinpols.batch.orchestrator.application.contract.response.LineageEvidenceResponse.PipelineInstance;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface LineageEvidenceMapper {

  JobInstance selectJobInstance(
      @Param("tenantId") String tenantId, @Param("jobInstanceId") Long jobInstanceId);

  JobInstance selectArchivedJobInstance(
      @Param("tenantId") String tenantId, @Param("jobInstanceId") Long jobInstanceId);

  List<PipelineInstance> selectPipelineInstances(
      @Param("tenantId") String tenantId, @Param("jobInstanceId") Long jobInstanceId);

  List<PipelineInstance> selectArchivedPipelineInstances(
      @Param("tenantId") String tenantId, @Param("jobInstanceId") Long jobInstanceId);

  List<FileRecord> selectFileRecords(
      @Param("tenantId") String tenantId,
      @Param("jobInstanceId") Long jobInstanceId,
      @Param("payloadFileId") Long payloadFileId);

  List<FileRecord> selectArchivedFileRecords(
      @Param("tenantId") String tenantId,
      @Param("jobInstanceId") Long jobInstanceId,
      @Param("payloadFileId") Long payloadFileId);

  List<DispatchRecord> selectDispatchRecords(
      @Param("tenantId") String tenantId,
      @Param("jobInstanceId") Long jobInstanceId,
      @Param("fileIds") List<Long> fileIds);

  List<DispatchRecord> selectArchivedDispatchRecords(
      @Param("tenantId") String tenantId,
      @Param("jobInstanceId") Long jobInstanceId,
      @Param("fileIds") List<Long> fileIds);
}
