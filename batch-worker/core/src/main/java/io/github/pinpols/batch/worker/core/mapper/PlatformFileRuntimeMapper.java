package io.github.pinpols.batch.worker.core.mapper;

import io.github.pinpols.batch.worker.core.param.PlatformFileAuditInsertParam;
import io.github.pinpols.batch.worker.core.param.PlatformFileErrorInsertParam;
import io.github.pinpols.batch.worker.core.param.PlatformFileInsertParam;
import io.github.pinpols.batch.worker.core.param.PlatformPipelineFinishParam;
import io.github.pinpols.batch.worker.core.param.PlatformPipelineInsertParam;
import io.github.pinpols.batch.worker.core.param.PlatformStepRunFinishParam;
import io.github.pinpols.batch.worker.core.param.PlatformStepRunInsertParam;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface PlatformFileRuntimeMapper {

  PlatformFileRecordView selectFileRecord(
      @Param("tenantId") String tenantId, @Param("fileId") Long fileId);

  Long countFileRecordByStoragePath(
      @Param("tenantId") String tenantId,
      @Param("storageBucket") String storageBucket,
      @Param("storagePath") String storagePath);

  PlatformFileRecordView selectFileRecordByStoragePath(
      @Param("tenantId") String tenantId,
      @Param("storageBucket") String storageBucket,
      @Param("storagePath") String storagePath);

  PlatformFileTemplateConfigView selectLatestTemplateConfig(
      @Param("tenantId") String tenantId,
      @Param("templateCode") String templateCode,
      @Param("templateType") String templateType);

  PlatformPipelineRunKeyView insertPipelineInstance(PlatformPipelineInsertParam params);

  int bindFileToPipelineInstance(
      @Param("tenantId") String tenantId,
      @Param("pipelineInstanceId") Long pipelineInstanceId,
      @Param("runStartedAt") OffsetDateTime runStartedAt,
      @Param("fileId") Long fileId);

  int updatePipelineStage(
      @Param("pipelineInstanceId") Long pipelineInstanceId,
      @Param("runStartedAt") OffsetDateTime runStartedAt,
      @Param("currentStage") String currentStage,
      @Param("lastSuccessStage") String lastSuccessStage);

  int markPipelineSuccess(PlatformPipelineFinishParam params);

  int markPipelineFailed(PlatformPipelineFinishParam params);

  int markPipelineCompensating(
      @Param("pipelineInstanceId") Long pipelineInstanceId,
      @Param("runStartedAt") OffsetDateTime runStartedAt,
      @Param("runStatus") String runStatus);

  Integer selectNextStepRunSeq(
      @Param("pipelineInstanceId") Long pipelineInstanceId, @Param("stepCode") String stepCode);

  int lockStepRunSequence(
      @Param("pipelineInstanceId") Long pipelineInstanceId, @Param("stepCode") String stepCode);

  List<String> selectSucceededStepCodes(@Param("pipelineInstanceId") Long pipelineInstanceId);

  String selectLatestSucceededStepOutputSummary(
      @Param("pipelineInstanceId") Long pipelineInstanceId, @Param("stepCode") String stepCode);

  int insertStepRun(PlatformStepRunInsertParam params);

  int finishStepRun(PlatformStepRunFinishParam params);

  /** 在分配文件版本前锁住租户与逻辑文件身份；事务结束自动释放，也覆盖尚无文件行的首代登记。 */
  int lockFileGeneration(@Param("tenantId") String tenantId, @Param("fileCode") String fileCode);

  Integer selectMaxFileGenerationNo(
      @Param("tenantId") String tenantId, @Param("fileCode") String fileCode);

  int markHistoricalFileNotLatest(
      @Param("tenantId") String tenantId, @Param("fileCode") String fileCode);

  int insertFileRecord(PlatformFileInsertParam params);

  String selectFileStatus(@Param("fileId") Long fileId);

  int updateFileRecordStatus(
      @Param("fileId") Long fileId,
      @Param("fileStatus") String fileStatus,
      @Param("metadataJson") String metadataJson);

  int updateFileRecordMetadata(
      @Param("fileId") Long fileId, @Param("metadataJson") String metadataJson);

  int insertFileErrorRecord(PlatformFileErrorInsertParam params);

  int insertFileAuditLog(PlatformFileAuditInsertParam params);
}
