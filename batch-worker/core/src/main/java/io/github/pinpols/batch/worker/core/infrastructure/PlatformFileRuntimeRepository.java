package io.github.pinpols.batch.worker.core.infrastructure;

import io.github.pinpols.batch.worker.core.domain.PipelineStepDefinition;
import io.github.pinpols.batch.worker.core.domain.PipelineStepTemplate;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Worker 文件与 Pipeline 运行态的兼容门面。
 *
 * <p>公共契约保持稳定，内部按 Pipeline 定义、Pipeline 运行态、文件记录及审计错误四类职责委派。事务入口仍保留在本门面，避免拆分改变既有 Spring AOP 边界。
 */
@Repository
public class PlatformFileRuntimeRepository {

  private final PlatformPipelineDefinitionRepository pipelineDefinitions;
  private final PlatformPipelineRunRepository pipelineRuns;
  private final PlatformFileRecordRepository fileRecords;
  private final PlatformFileAuditRepository fileAudits;

  public PlatformFileRuntimeRepository(PlatformFileRuntimeMapper mapper) {
    this.pipelineDefinitions = new PlatformPipelineDefinitionRepository(mapper);
    this.pipelineRuns = new PlatformPipelineRunRepository(mapper);
    this.fileRecords = new PlatformFileRecordRepository(mapper);
    this.fileAudits = new PlatformFileAuditRepository(mapper);
  }

  public Map<String, Object> loadFileRecord(String tenantId, Long fileId) {
    return fileRecords.loadFileRecord(tenantId, fileId);
  }

  public boolean existsFileRecordByStoragePath(
      String tenantId, String storageBucket, String storagePath) {
    return fileRecords.existsFileRecordByStoragePath(tenantId, storageBucket, storagePath);
  }

  public Map<String, Object> loadFileRecordByStoragePath(
      String tenantId, String storageBucket, String storagePath) {
    return fileRecords.loadFileRecordByStoragePath(tenantId, storageBucket, storagePath);
  }

  public Map<String, Object> loadLatestTemplateConfig(
      String tenantId, String templateCode, String templateType) {
    return pipelineDefinitions.loadLatestTemplateConfig(tenantId, templateCode, templateType);
  }

  public Map<String, Object> loadChannelConfig(String tenantId, String channelCode) {
    return pipelineDefinitions.loadChannelConfig(tenantId, channelCode);
  }

  public Long ensurePipelineDefinition(
      String tenantId,
      String jobCode,
      String pipelineType,
      String workerGroup,
      String description,
      List<PipelineStepTemplate> defaultSteps) {
    return pipelineDefinitions.ensurePipelineDefinition(
        tenantId, jobCode, pipelineType, workerGroup, description, defaultSteps);
  }

  public List<PipelineStepDefinition> loadPipelineSteps(Long pipelineDefinitionId) {
    return pipelineDefinitions.loadPipelineSteps(pipelineDefinitionId);
  }

  public record CreatePipelineInstanceParam(
      String tenantId,
      Long pipelineDefinitionId,
      String jobCode,
      String pipelineType,
      Long fileId,
      Long relatedJobInstanceId,
      String currentStage,
      String traceId) {}

  public Long createPipelineInstance(CreatePipelineInstanceParam param) {
    return pipelineRuns.createPipelineInstance(param);
  }

  public void bindFileToPipelineInstance(Long pipelineInstanceId, Long fileId) {
    pipelineRuns.bindFileToPipelineInstance(pipelineInstanceId, fileId);
  }

  public void updatePipelineStage(
      Long pipelineInstanceId, String currentStage, String lastSuccessStage) {
    pipelineRuns.updatePipelineStage(pipelineInstanceId, currentStage, lastSuccessStage);
  }

  public void markPipelineSuccess(
      Long pipelineInstanceId, String currentStage, String lastSuccessStage) {
    pipelineRuns.markPipelineSuccess(pipelineInstanceId, currentStage, lastSuccessStage);
  }

  public void markPipelineCompensating(Long pipelineInstanceId) {
    pipelineRuns.markPipelineCompensating(pipelineInstanceId);
  }

  public void markPipelineFailed(
      Long pipelineInstanceId, String currentStage, String lastSuccessStage) {
    pipelineRuns.markPipelineFailed(pipelineInstanceId, currentStage, lastSuccessStage);
  }

  public Set<String> loadSucceededStepCodes(Long pipelineInstanceId) {
    return pipelineRuns.loadSucceededStepCodes(pipelineInstanceId);
  }

  public Map<String, Object> loadLatestSucceededStepOutputSummary(
      Long pipelineInstanceId, String stepCode) {
    return pipelineRuns.loadLatestSucceededStepOutputSummary(pipelineInstanceId, stepCode);
  }

  public Long startStepRun(
      Long pipelineInstanceId, String stepCode, String stageCode, Object inputSummary) {
    return pipelineRuns.startStepRun(pipelineInstanceId, stepCode, stageCode, inputSummary);
  }

  public void finishStepRunSuccess(Long stepRunId, Object outputSummary) {
    pipelineRuns.finishStepRunSuccess(stepRunId, outputSummary);
  }

  public void finishStepRunFailure(
      Long stepRunId, String errorCode, String errorMessage, Object outputSummary) {
    pipelineRuns.finishStepRunFailure(stepRunId, errorCode, errorMessage, outputSummary);
  }

  public void finishStepRunFailure(
      Long stepRunId,
      String errorCode,
      String errorMessage,
      String errorKey,
      String errorArgs,
      Object outputSummary) {
    pipelineRuns.finishStepRunFailure(
        stepRunId, errorCode, errorMessage, errorKey, errorArgs, outputSummary);
  }

  @Transactional
  public Long createFileRecord(FileRecordParam param) {
    return fileRecords.createFileRecord(param);
  }

  public void updateFileStatus(Long fileId, String fileStatus, Object metadata) {
    fileRecords.updateFileStatus(fileId, fileStatus, metadata);
  }

  public String currentFileStatus(Long fileId) {
    return fileRecords.currentFileStatus(fileId);
  }

  public void updateFileMetadata(Long fileId, Object metadata) {
    fileRecords.updateFileMetadata(fileId, metadata);
  }

  public Long insertFileErrorRecord(FileErrorRecordParam param) {
    return fileAudits.insertFileErrorRecord(param);
  }

  public List<Map<String, Object>> loadFileErrorRecords(
      String tenantId, Long fileId, String errorCode, String errorStage, int limit) {
    return fileAudits.loadFileErrorRecords(tenantId, fileId, errorCode, errorStage, limit);
  }

  public void appendAudit(FileAuditParam param) {
    fileAudits.appendAudit(param);
  }

  public Long toLong(Object value) {
    return PlatformRuntimeValues.toLong(value);
  }

  public Instant toInstant(Object value) {
    return PlatformRuntimeValues.toInstant(value);
  }
}
