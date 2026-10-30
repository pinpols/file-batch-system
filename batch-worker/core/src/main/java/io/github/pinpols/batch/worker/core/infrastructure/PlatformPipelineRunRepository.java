package io.github.pinpols.batch.worker.core.infrastructure;

import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.toJson;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.truncate;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.enums.PipelineRunStatus;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import io.github.pinpols.batch.worker.core.param.PlatformPipelineInsertParam;
import io.github.pinpols.batch.worker.core.param.PlatformStepRunFinishParam;
import io.github.pinpols.batch.worker.core.param.PlatformStepRunInsertParam;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Pipeline 实例与步骤运行记录的数据访问协作者。 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class PlatformPipelineRunRepository {

  private final PlatformFileRuntimeMapper mapper;

  public Long createPipelineInstance(CreatePipelineInstanceParam param) {
    if (!Texts.hasText(param.tenantId()) || !Texts.hasText(param.systemPipelineCode())) {
      return null;
    }
    PlatformPipelineInsertParam values = PlatformPipelineInsertParam.builder()
        .tenantId(param.tenantId())
        .systemPipelineCode(param.systemPipelineCode())
        .systemPipelineVersion(param.systemPipelineVersion())
        .jobCode(param.jobCode())
        .pipelineType(param.pipelineType())
        .fileId(param.fileId())
        .relatedJobInstanceId(param.relatedJobInstanceId())
        .currentStage(param.currentStage())
        .traceId(param.traceId())
        .runStatus(PipelineRunStatus.RUNNING.name())
        .build();
    mapper.insertPipelineInstance(values);
    return values.getId();
  }

  public void bindFileToPipelineInstance(String tenantId, Long pipelineInstanceId, Long fileId) {
    boolean pipelineFileAssociationIsComplete = EmptyChecks.isNotNull(tenantId)
        && EmptyChecks.isNotNull(pipelineInstanceId)
        && EmptyChecks.isNotNull(fileId);
    if (pipelineFileAssociationIsComplete) {
      mapper.bindFileToPipelineInstance(tenantId, pipelineInstanceId, fileId);
    }
  }

  public void updatePipelineStage(
      Long pipelineInstanceId, String currentStage, String lastSuccessStage) {
    if (pipelineInstanceId != null) {
      mapper.updatePipelineStage(pipelineInstanceId, currentStage, lastSuccessStage);
    }
  }

  public void markPipelineSuccess(
      Long pipelineInstanceId, String currentStage, String lastSuccessStage) {
    if (pipelineInstanceId != null) {
      mapper.markPipelineSuccess(
          pipelineInstanceId, currentStage, lastSuccessStage, PipelineRunStatus.SUCCESS.name());
    }
  }

  public void markPipelineCompensating(Long pipelineInstanceId) {
    if (pipelineInstanceId != null) {
      mapper.markPipelineCompensating(pipelineInstanceId, PipelineRunStatus.COMPENSATING.name());
    }
  }

  public void markPipelineFailed(
      Long pipelineInstanceId, String currentStage, String lastSuccessStage) {
    if (pipelineInstanceId != null) {
      mapper.markPipelineFailed(
          pipelineInstanceId, currentStage, lastSuccessStage, PipelineRunStatus.FAILED.name());
    }
  }

  public Set<String> loadSucceededStepCodes(Long pipelineInstanceId) {
    if (pipelineInstanceId == null) {
      return Set.of();
    }
    List<String> stepCodes = mapper.selectSucceededStepCodes(pipelineInstanceId);
    return stepCodes == null || stepCodes.isEmpty() ? Set.of() : new HashSet<>(stepCodes);
  }

  public Map<String, Object> loadLatestSucceededStepOutputSummary(
      Long pipelineInstanceId, String stepCode) {
    if (pipelineInstanceId == null || !Texts.hasText(stepCode)) {
      return Map.of();
    }
    String json = mapper.selectLatestSucceededStepOutputSummary(pipelineInstanceId, stepCode);
    if (!Texts.hasText(json)) {
      return Map.of();
    }
    try {
      Map<String, Object> parsed =
          JsonUtils.fromJson(json, new TypeReference<Map<String, Object>>() {});
      return parsed == null ? Map.of() : parsed;
    } catch (RuntimeException ex) {
      log.warn(
          "failed to parse pipeline_step_run output_summary for skip carry-forward:"
              + " pipelineInstanceId={}, stepCode={}, message={}",
          pipelineInstanceId,
          stepCode,
          SwallowedExceptionLogger.summary(ex));
      return Map.of();
    }
  }

  @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
  public Long startStepRun(
      Long pipelineInstanceId, String stepCode, String stageCode, Object inputSummary) {
    if (pipelineInstanceId == null || !Texts.hasText(stepCode) || !Texts.hasText(stageCode)) {
      return null;
    }
    PlatformStepRunInsertParam values = PlatformStepRunInsertParam.builder()
        .pipelineInstanceId(pipelineInstanceId)
        .stepCode(stepCode)
        .stageCode(stageCode)
        .stepStatus(PipelineRunStatus.RUNNING.name())
        .inputSummaryJson(toJson(inputSummary))
        .build();
    mapper.lockStepRunSequence(pipelineInstanceId, stepCode);
    Integer runSeq = mapper.selectNextStepRunSeq(pipelineInstanceId, stepCode);
    values.setRunSeq(runSeq);
    mapper.insertStepRun(values);
    return values.getId();
  }

  public void finishStepRunSuccess(Long stepRunId, Object outputSummary) {
    finishStepRun(stepRunId, "SUCCESS", null, null, outputSummary);
  }

  public void finishStepRunFailure(
      Long stepRunId, String errorCode, String errorMessage, Object outputSummary) {
    finishStepRunFailure(stepRunId, errorCode, errorMessage, null, null, outputSummary);
  }

  public void finishStepRunFailure(
      Long stepRunId,
      String errorCode,
      String errorMessage,
      String errorKey,
      String errorArgs,
      Object outputSummary) {
    FinishStepRunParam param = FinishStepRunParam.builder()
        .stepRunId(stepRunId)
        .status("FAILED")
        .errorCode(errorCode)
        .errorMessage(errorMessage)
        .errorKey(errorKey)
        .errorArgs(errorArgs)
        .outputSummary(outputSummary)
        .build();
    finishStepRun(param);
  }

  private void finishStepRun(
      Long stepRunId, String status, String errorCode, String errorMessage, Object outputSummary) {
    FinishStepRunParam param = FinishStepRunParam.builder()
        .stepRunId(stepRunId)
        .status(status)
        .errorCode(errorCode)
        .errorMessage(errorMessage)
        .outputSummary(outputSummary)
        .build();
    finishStepRun(param);
  }

  private void finishStepRun(FinishStepRunParam param) {
    if (param.stepRunId() == null) {
      return;
    }
    String outputSummaryJson = toJson(param.outputSummary());
    String boundedErrorMessage = truncate(param.errorMessage(), 1024);
    PlatformStepRunFinishParam statementParams = PlatformStepRunFinishParam.builder()
        .stepRunId(param.stepRunId())
        .status(param.status())
        .outputSummaryJson(outputSummaryJson)
        .errorCode(param.errorCode())
        .errorMessage(boundedErrorMessage)
        .errorKey(param.errorKey())
        .errorArgs(param.errorArgs())
        .build();
    mapper.finishStepRun(statementParams);
  }

  @Builder
  private record FinishStepRunParam(
      Long stepRunId,
      String status,
      String errorCode,
      String errorMessage,
      String errorKey,
      String errorArgs,
      Object outputSummary) {}
}
