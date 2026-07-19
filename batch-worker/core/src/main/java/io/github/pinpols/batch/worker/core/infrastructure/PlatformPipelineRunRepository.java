package io.github.pinpols.batch.worker.core.infrastructure;

import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.CURRENT_STAGE;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.FILE_ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.PIPELINE_DEFINITION_ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.PIPELINE_INSTANCE_ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.TENANT_ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.params;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.toJson;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.toLong;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.truncate;

import io.github.pinpols.batch.common.enums.PipelineRunStatus;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;

/** Pipeline 实例与步骤运行记录的数据访问协作者。 */
@RequiredArgsConstructor
@Slf4j
final class PlatformPipelineRunRepository {

  private final PlatformFileRuntimeMapper mapper;

  Long createPipelineInstance(PlatformFileRuntimeRepository.CreatePipelineInstanceParam param) {
    if (!Texts.hasText(param.tenantId()) || param.pipelineDefinitionId() == null) {
      return null;
    }
    Map<String, Object> values =
        params(
            TENANT_ID,
            param.tenantId(),
            PIPELINE_DEFINITION_ID,
            param.pipelineDefinitionId(),
            "jobCode",
            param.jobCode(),
            "pipelineType",
            param.pipelineType(),
            FILE_ID,
            param.fileId(),
            "relatedJobInstanceId",
            param.relatedJobInstanceId(),
            CURRENT_STAGE,
            param.currentStage(),
            "traceId",
            param.traceId(),
            "runStatus",
            PipelineRunStatus.RUNNING.name());
    mapper.insertPipelineInstance(values);
    return toLong(values.get(ID));
  }

  void bindFileToPipelineInstance(Long pipelineInstanceId, Long fileId) {
    if (pipelineInstanceId != null && fileId != null) {
      mapper.bindFileToPipelineInstance(
          params(PIPELINE_INSTANCE_ID, pipelineInstanceId, FILE_ID, fileId));
    }
  }

  void updatePipelineStage(Long pipelineInstanceId, String currentStage, String lastSuccessStage) {
    if (pipelineInstanceId != null) {
      mapper.updatePipelineStage(
          params(
              PIPELINE_INSTANCE_ID,
              pipelineInstanceId,
              CURRENT_STAGE,
              currentStage,
              "lastSuccessStage",
              lastSuccessStage));
    }
  }

  void markPipelineSuccess(Long pipelineInstanceId, String currentStage, String lastSuccessStage) {
    if (pipelineInstanceId != null) {
      mapper.markPipelineSuccess(
          params(
              PIPELINE_INSTANCE_ID,
              pipelineInstanceId,
              CURRENT_STAGE,
              currentStage,
              "lastSuccessStage",
              lastSuccessStage,
              "runStatus",
              PipelineRunStatus.SUCCESS.name()));
    }
  }

  void markPipelineCompensating(Long pipelineInstanceId) {
    if (pipelineInstanceId != null) {
      mapper.markPipelineCompensating(
          params(
              PIPELINE_INSTANCE_ID,
              pipelineInstanceId,
              "runStatus",
              PipelineRunStatus.COMPENSATING.name()));
    }
  }

  void markPipelineFailed(Long pipelineInstanceId, String currentStage, String lastSuccessStage) {
    if (pipelineInstanceId != null) {
      mapper.markPipelineFailed(
          params(
              PIPELINE_INSTANCE_ID,
              pipelineInstanceId,
              CURRENT_STAGE,
              currentStage,
              "lastSuccessStage",
              lastSuccessStage,
              "runStatus",
              PipelineRunStatus.FAILED.name()));
    }
  }

  Set<String> loadSucceededStepCodes(Long pipelineInstanceId) {
    if (pipelineInstanceId == null) {
      return Set.of();
    }
    List<String> stepCodes =
        mapper.selectSucceededStepCodes(params(PIPELINE_INSTANCE_ID, pipelineInstanceId));
    return stepCodes == null || stepCodes.isEmpty() ? Set.of() : new HashSet<>(stepCodes);
  }

  Map<String, Object> loadLatestSucceededStepOutputSummary(
      Long pipelineInstanceId, String stepCode) {
    if (pipelineInstanceId == null || !Texts.hasText(stepCode)) {
      return Map.of();
    }
    String json =
        mapper.selectLatestSucceededStepOutputSummary(
            params(PIPELINE_INSTANCE_ID, pipelineInstanceId, "stepCode", stepCode));
    if (!Texts.hasText(json)) {
      return Map.of();
    }
    try {
      Map<String, Object> parsed = JsonUtils.fromJson(json, Map.class);
      return parsed == null ? Map.of() : parsed;
    } catch (RuntimeException ex) {
      log.warn(
          "failed to parse pipeline_step_run output_summary for skip carry-forward:"
              + " pipelineInstanceId={}, stepCode={}, message={}",
          pipelineInstanceId,
          stepCode,
          ex.getMessage());
      return Map.of();
    }
  }

  Long startStepRun(
      Long pipelineInstanceId, String stepCode, String stageCode, Object inputSummary) {
    if (pipelineInstanceId == null || !Texts.hasText(stepCode) || !Texts.hasText(stageCode)) {
      return null;
    }
    Map<String, Object> values =
        params(
            PIPELINE_INSTANCE_ID,
            pipelineInstanceId,
            "stepCode",
            stepCode,
            "stageCode",
            stageCode,
            "stepStatus",
            PipelineRunStatus.RUNNING.name(),
            "inputSummaryJson",
            toJson(inputSummary));
    for (int index = 0; index < 5; index++) {
      try {
        mapper.insertStepRun(values);
        return toLong(values.get(ID));
      } catch (DuplicateKeyException ex) {
        values.remove(ID);
        if (index == 4) {
          throw ex;
        }
      }
    }
    return null;
  }

  void finishStepRunSuccess(Long stepRunId, Object outputSummary) {
    finishStepRun(stepRunId, "SUCCESS", null, null, outputSummary);
  }

  void finishStepRunFailure(
      Long stepRunId, String errorCode, String errorMessage, Object outputSummary) {
    finishStepRunFailure(stepRunId, errorCode, errorMessage, null, null, outputSummary);
  }

  void finishStepRunFailure(
      Long stepRunId,
      String errorCode,
      String errorMessage,
      String errorKey,
      String errorArgs,
      Object outputSummary) {
    finishStepRun(
        FinishStepRunParam.builder()
            .stepRunId(stepRunId)
            .status("FAILED")
            .errorCode(errorCode)
            .errorMessage(errorMessage)
            .errorKey(errorKey)
            .errorArgs(errorArgs)
            .outputSummary(outputSummary)
            .build());
  }

  private void finishStepRun(
      Long stepRunId, String status, String errorCode, String errorMessage, Object outputSummary) {
    finishStepRun(
        FinishStepRunParam.builder()
            .stepRunId(stepRunId)
            .status(status)
            .errorCode(errorCode)
            .errorMessage(errorMessage)
            .outputSummary(outputSummary)
            .build());
  }

  private void finishStepRun(FinishStepRunParam param) {
    if (param.stepRunId() == null) {
      return;
    }
    mapper.finishStepRun(
        params(
            "stepRunId", param.stepRunId(),
            "status", param.status(),
            "outputSummaryJson", toJson(param.outputSummary()),
            "errorCode", param.errorCode(),
            "errorMessage", truncate(param.errorMessage(), 1024),
            "errorKey", param.errorKey(),
            "errorArgs", param.errorArgs()));
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
