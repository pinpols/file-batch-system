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
import io.github.pinpols.batch.worker.core.mapper.PlatformPipelineRunKeyView;
import io.github.pinpols.batch.worker.core.param.PlatformPipelineFinishParam;
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

  public PipelineRunFence createPipelineInstance(CreatePipelineInstanceParam param) {
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
    PlatformPipelineRunKeyView created = mapper.insertPipelineInstance(values);
    return EmptyChecks.isNull(created)
        ? null
        : new PipelineRunFence(created.id(), created.startedAt());
  }

  public boolean bindFileToPipelineInstance(String tenantId, PipelineRunFence run, Long fileId) {
    boolean pipelineFileAssociationIsComplete = EmptyChecks.isNotNull(tenantId)
        && EmptyChecks.isNotNull(run)
        && EmptyChecks.isNotNull(fileId);
    if (!pipelineFileAssociationIsComplete) {
      return false;
    }
    int updated = mapper.bindFileToPipelineInstance(
        tenantId, run.pipelineInstanceId(), run.startedAt(), fileId);
    return appliedOrLogStale(updated, run, "bindFileToPipelineInstance");
  }

  /*
   * 以下运行态写入都带非终态与运行代际谓词：Orchestrator 已扫为终态或已被新 invocation 接管时返回 false。
   * pipeline_instance 只承载观测，job_task 状态仍经 REPORT 收敛，因此迟到写入只记录不抛错。
   */

  public boolean updatePipelineStage(
      PipelineRunFence run, String currentStage, String lastSuccessStage) {
    if (EmptyChecks.isNull(run)) {
      return false;
    }
    int updated = mapper.updatePipelineStage(
        run.pipelineInstanceId(), run.startedAt(), currentStage, lastSuccessStage);
    return appliedOrLogStale(updated, run, "updatePipelineStage");
  }

  public boolean markPipelineSuccess(
      PipelineRunFence run, String currentStage, String lastSuccessStage) {
    if (EmptyChecks.isNull(run)) {
      return false;
    }
    PlatformPipelineFinishParam values =
        finishParam(run, currentStage, lastSuccessStage, PipelineRunStatus.SUCCESS);
    return appliedOrLogStale(mapper.markPipelineSuccess(values), run, "markPipelineSuccess");
  }

  public boolean markPipelineCompensating(PipelineRunFence run) {
    if (EmptyChecks.isNull(run)) {
      return false;
    }
    int updated = mapper.markPipelineCompensating(
        run.pipelineInstanceId(), run.startedAt(), PipelineRunStatus.COMPENSATING.name());
    return appliedOrLogStale(updated, run, "markPipelineCompensating");
  }

  public boolean markPipelineFailed(
      PipelineRunFence run, String currentStage, String lastSuccessStage) {
    if (EmptyChecks.isNull(run)) {
      return false;
    }
    PlatformPipelineFinishParam values =
        finishParam(run, currentStage, lastSuccessStage, PipelineRunStatus.FAILED);
    return appliedOrLogStale(mapper.markPipelineFailed(values), run, "markPipelineFailed");
  }

  private static PlatformPipelineFinishParam finishParam(
      PipelineRunFence run,
      String currentStage,
      String lastSuccessStage,
      PipelineRunStatus runStatus) {
    return PlatformPipelineFinishParam.builder()
        .pipelineInstanceId(run.pipelineInstanceId())
        .runStartedAt(run.startedAt())
        .currentStage(currentStage)
        .lastSuccessStage(lastSuccessStage)
        .runStatus(runStatus.name())
        .build();
  }

  private static boolean appliedOrLogStale(int updated, PipelineRunFence run, String operation) {
    if (updated > 0) {
      return true;
    }
    log.warn(
        "stale pipeline write ignored: operation={}, pipelineInstanceId={}, runStartedAt={}",
        operation,
        run.pipelineInstanceId(),
        run.startedAt());
    return false;
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
