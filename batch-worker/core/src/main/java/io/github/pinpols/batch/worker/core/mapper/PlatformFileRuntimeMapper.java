package io.github.pinpols.batch.worker.core.mapper;

import java.util.List;
import java.util.Map;

public interface PlatformFileRuntimeMapper {

  Map<String, Object> selectFileRecord(Map<String, Object> params);

  Long countFileRecordByStoragePath(Map<String, Object> params);

  Map<String, Object> selectFileRecordByStoragePath(Map<String, Object> params);

  Map<String, Object> selectLatestTemplateConfig(Map<String, Object> params);

  Map<String, Object> selectChannelConfig(Map<String, Object> params);

  Long selectLatestPipelineDefinitionId(Map<String, Object> params);

  int insertPipelineDefinition(Map<String, Object> params);

  List<Map<String, Object>> selectPipelineStepDefinitions(Map<String, Object> params);

  int insertPipelineStepDefinition(Map<String, Object> params);

  int insertPipelineInstance(Map<String, Object> params);

  int bindFileToPipelineInstance(Map<String, Object> params);

  int updatePipelineStage(Map<String, Object> params);

  int markPipelineSuccess(Map<String, Object> params);

  int markPipelineFailed(Map<String, Object> params);

  int markPipelineCompensating(Map<String, Object> params);

  Integer selectNextStepRunSeq(Map<String, Object> params);

  int insertStepRun(Map<String, Object> params);

  int finishStepRun(Map<String, Object> params);

  Integer selectMaxFileGenerationNo(Map<String, Object> params);

  int markHistoricalFileNotLatest(Map<String, Object> params);

  int insertFileRecord(Map<String, Object> params);

  String selectFileStatus(Map<String, Object> params);

  int updateFileRecordStatus(Map<String, Object> params);

  int updateFileRecordMetadata(Map<String, Object> params);

  int insertFileErrorRecord(Map<String, Object> params);

  List<Map<String, Object>> selectFileErrorRecords(Map<String, Object> params);

  int insertFileAuditLog(Map<String, Object> params);
}
