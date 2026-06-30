package io.github.pinpols.batch.orchestrator.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface LineageEvidenceMapper {

  Map<String, Object> selectJobInstance(
      @Param("tenantId") String tenantId, @Param("jobInstanceId") Long jobInstanceId);

  Map<String, Object> selectArchivedJobInstance(
      @Param("tenantId") String tenantId, @Param("jobInstanceId") Long jobInstanceId);

  List<Map<String, Object>> selectPipelineInstances(
      @Param("tenantId") String tenantId, @Param("jobInstanceId") Long jobInstanceId);

  List<Map<String, Object>> selectArchivedPipelineInstances(
      @Param("tenantId") String tenantId, @Param("jobInstanceId") Long jobInstanceId);

  List<Map<String, Object>> selectFileRecords(
      @Param("tenantId") String tenantId,
      @Param("jobInstanceId") Long jobInstanceId,
      @Param("payloadFileId") Long payloadFileId);

  List<Map<String, Object>> selectDispatchRecords(
      @Param("tenantId") String tenantId,
      @Param("jobInstanceId") Long jobInstanceId,
      @Param("fileIds") List<Long> fileIds);

  List<Map<String, Object>> selectArchivedDispatchRecords(
      @Param("tenantId") String tenantId,
      @Param("jobInstanceId") Long jobInstanceId,
      @Param("fileIds") List<Long> fileIds);
}
