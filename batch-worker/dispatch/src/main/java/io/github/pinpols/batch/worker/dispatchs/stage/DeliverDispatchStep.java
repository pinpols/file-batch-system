package io.github.pinpols.batch.worker.dispatchs.stage;

import static io.github.pinpols.batch.worker.core.support.AbstractStageExecutor.ERROR_OBJECT_MAPPER;

import io.github.pinpols.batch.common.enums.FileDispatchStatus;
import io.github.pinpols.batch.common.enums.FileReceiptStatus;
import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.service.DryRunGuard;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchRecordSnapshot;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchRuntimeKeys;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchSecretBlockedException;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchSecretResolver;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchChannelGateway;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchCommand;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchManifestRef;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchReadbackVerifier;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Dispatch pipeline 的 DISPATCH 阶段：通过 {@link DispatchChannelGateway} 执行实际文件投递。
 *
 * <p><b>恢复安全</b>：持久化的 {@code CREATED} 代表一次外发已开始，恢复时按结果未知阻止自动重发；
 * 已持久化的 {@code SENT}/{@code ACKED} 则继续后续阶段，不再次访问外部渠道。
 *
 * <p><b>结果处理</b>：投递结果确定时按原有单次重试策略处理；外部结果不确定或成功结果无法持久化时，停止自动重试与补偿，交由人工核对。
 */
@Slf4j
@Component
public class DeliverDispatchStep implements DispatchStageStep {

  private static final String ERROR_CODE_SEND_FAILED = "DISPATCH_SEND_FAILED";
  private static final String ERROR_CODE_READBACK_MISMATCH = "DISPATCH_READBACK_MISMATCH";

  private final FileDispatchRepository fileDispatchRepository;
  private final DispatchChannelGateway dispatchChannelGateway;
  private final PlatformFileRecordRepository fileRecords;
  private final DispatchSecretResolver dispatchSecretResolver;

  public DeliverDispatchStep(
      FileDispatchRepository fileDispatchRepository,
      DispatchChannelGateway dispatchChannelGateway,
      PlatformFileRecordRepository fileRecords,
      DispatchSecretResolver dispatchSecretResolver) {
    this.fileDispatchRepository = fileDispatchRepository;
    this.dispatchChannelGateway = dispatchChannelGateway;
    this.fileRecords = fileRecords;
    this.dispatchSecretResolver = dispatchSecretResolver;
  }

  @Override
  public DispatchStage stage() {
    return DispatchStage.DISPATCH;
  }

  @Override
  public DispatchStageResult execute(DispatchJobContext context) {
    Object payload = EmptyChecks.isNull(context)
        ? null
        : context.getAttributes().get(DispatchRuntimeKeys.DISPATCH_PAYLOAD);
    if (!(payload instanceof DispatchPayload dispatchPayload)) {
      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_LOAD_NO_PAYLOAD",
          "error.dispatch.payload_missing",
          new Object[0],
          "dispatch payload missing",
          ERROR_OBJECT_MAPPER);
    }
    Map<String, Object> attrs = context.getAttributes();
    Long fileId = PlatformRuntimeValues.toLong(attrs.get(PipelineRuntimeKeys.FILE_ID));
    @SuppressWarnings("unchecked")
    Map<String, Object> fileRecord =
        (Map<String, Object>) attrs.get(PipelineRuntimeKeys.FILE_RECORD);
    @SuppressWarnings("unchecked")
    Map<String, Object> channelConfig =
        (Map<String, Object>) attrs.get(PipelineRuntimeKeys.CHANNEL_CONFIG);
    if (fileId == null || fileRecord == null || channelConfig == null) {
      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_PREPARE_MISSING",
          "error.dispatch.deliver.context_missing",
          new Object[0],
          "file or channel context missing",
          ERROR_OBJECT_MAPPER);
    }
    DryRunGuard guard = DryRunGuard.fromAttributes(attrs);
    if (guard.isDryRun()) {
      DispatchResult dryRunResult = DispatchResult.success(
          "DRY_RUN", "DRY_RUN_RECEIPT_" + dispatchPayload.channelCode(), false);
      DispatchInvocationSupport.propagateIdentifiers(context, dryRunResult);
      attrs.put(DispatchRuntimeKeys.DISPATCH_RECORD, dispatchPayload);
      attrs.put(PipelineRuntimeKeys.DRY_RUN_SKIPPED, "DISPATCH_EXTERNAL_DELIVERY");
      return DispatchStageResult.success(stage());
    }
    try {
      Set<String> resolvedSecretKeys = dispatchSecretResolver.resolveInto(
          context.getTenantId(),
          dispatchPayload.channelCode(),
          readRawPayload(context),
          channelConfig);
      try {
        return deliver(context, dispatchPayload, attrs, fileId, fileRecord, channelConfig);
      } finally {
        resolvedSecretKeys.forEach(channelConfig::remove);
      }
    } catch (DispatchSecretBlockedException exception) {
      attrs.put(DispatchRuntimeKeys.RETRY_REQUESTED, Boolean.FALSE);
      attrs.remove(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE);
      return DispatchStageResult.failure(
          stage(),
          exception.errorCode(),
          "error.dispatch.secret.blocked",
          new Object[0],
          exception.errorCode(),
          ERROR_OBJECT_MAPPER);
    }
  }

  private DispatchStageResult deliver(
      DispatchJobContext context,
      DispatchPayload dispatchPayload,
      Map<String, Object> attrs,
      Long fileId,
      Map<String, Object> fileRecord,
      Map<String, Object> channelConfig) {
    DispatchStageResult admissionFailure = prepareDeliveryAttempt(
        context, dispatchPayload, attrs, fileId);
    if (EmptyChecks.isNotNull(admissionFailure)) {
      return admissionFailure;
    }
    DispatchResult dispatchResult = DispatchInvocationSupport.invokeAndRecordIdentifiers(
        dispatchChannelGateway, context, fileRecord, channelConfig, dispatchPayload);
    Map<String, Object> fileMetadata = new LinkedHashMap<>();
    fileMetadata.put(DispatchRuntimeKeys.CHANNEL_CODE, dispatchPayload.channelCode());
    if (dispatchResult.externalRequestId() != null) {
      fileMetadata.put(DispatchRuntimeKeys.EXTERNAL_REQUEST_ID, dispatchResult.externalRequestId());
    }
    DispatchManifestRef manifestRef = dispatchResult.manifestRef();
    if (manifestRef != null) {
      manifestRef.putFileMetadata(fileMetadata);
    }
    fileRecords.updateFileStatus(fileId, FileStatus.DISPATCHING.code(), fileMetadata);
    if (!dispatchResult.success()) {
      String errorCode = dispatchResult.externalOutcomeUnknown()
          ? TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN
          : "DISPATCH_DELIVERY_FAILED";
      int markedFailed = fileDispatchRepository.markAttemptFailed(
          context.getTenantId(),
          fileId,
          dispatchPayload.channelCode(),
          errorCode,
          dispatchResult.message());
      if (markedFailed <= 0) {
        return externalOutcomeUnknown(
            context,
            fileId,
            dispatchPayload,
            "dispatch result could not be committed after the external call",
            false);
      }
      if (dispatchResult.externalOutcomeUnknown()) {
        return externalOutcomeUnknown(
            context, fileId, dispatchPayload, dispatchResult.message(), false);
      }
      attrs.put(DispatchRuntimeKeys.RETRY_REQUESTED, Boolean.TRUE);
      attrs.put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.RETRY.name());
      String messageForArguments = dispatchResult.message();
      String fallbackMessage = dispatchResult.message();
      return DispatchStageResult.failure(
          stage(),
          ERROR_CODE_SEND_FAILED,
          "error.dispatch.deliver.send_failed",
          new Object[] {messageForArguments},
          fallbackMessage,
          ERROR_OBJECT_MAPPER);
    }
    int updated = DispatchInvocationSupport.markSent(
        fileDispatchRepository, context, fileId, dispatchPayload, dispatchResult);
    if (updated <= 0) {
      return externalOutcomeUnknown(
          context,
          fileId,
          dispatchPayload,
          "external delivery succeeded but the SENT state could not be committed",
          true);
    }
    // ADR-041 Phase1.5:opt-in 投递后回读校验(readback_verify_enabled),抓传输损坏 / 半写。
    DispatchStageResult readbackFailure =
        verifyReadback(context, fileId, dispatchPayload, fileRecord, channelConfig);
    if (readbackFailure != null) {
      return readbackFailure;
    }
    attrs.put(DispatchRuntimeKeys.DISPATCH_RECORD, dispatchPayload);
    return DispatchStageResult.success(stage());
  }

  /** 已持久化结果先恢复，只有创建或取得下一次尝试授权后才允许进入外部渠道。 */
  private DispatchStageResult prepareDeliveryAttempt(
      DispatchJobContext context,
      DispatchPayload dispatchPayload,
      Map<String, Object> attrs,
      Long fileId) {
    Optional<DispatchRecordSnapshot> latestRecord = fileDispatchRepository.latestDispatchRecord(
        context.getTenantId(), fileId, dispatchPayload.channelCode());
    boolean newAttempt = !latestRecord.isPresent();
    if (newAttempt) {
      String tenantId = context.getTenantId();
      Long pipelineInstanceId =
          PlatformRuntimeValues.toLong(attrs.get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
      FileDispatchRepository.InsertDispatchParam insertParam =
          new FileDispatchRepository.InsertDispatchParam(
              tenantId,
              fileId,
              pipelineInstanceId,
              dispatchPayload.channelCode(),
              dispatchPayload.dispatchTarget(),
              dispatchPayload.receiptCode(),
              FileReceiptStatus.NONE.code(),
              dispatchPayload.externalRequestId());
      int inserted = fileDispatchRepository.insertDispatchRecord(insertParam);
      if (inserted <= 0) {
        latestRecord = fileDispatchRepository.latestDispatchRecord(
            context.getTenantId(), fileId, dispatchPayload.channelCode());
        if (!latestRecord.isPresent()) {
          return DispatchStageResult.failure(
              stage(),
              "DISPATCH_INSERT_FAILED",
              "error.dispatch.deliver.insert_failed",
              new Object[0],
              "failed to create dispatch record",
              ERROR_OBJECT_MAPPER);
        }
        newAttempt = false;
      }
    }
    if (!newAttempt) {
      DispatchRecordSnapshot snapshot = latestRecord.orElseThrow();
      if (TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN.equals(snapshot.errorCode())) {
        Long jobInstanceId =
            PlatformRuntimeValues.toLong(attrs.get(PipelineRuntimeKeys.JOB_INSTANCE_ID));
        Long pipelineInstanceId =
            PlatformRuntimeValues.toLong(attrs.get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
        int authorizedAttempt = fileDispatchRepository.beginVerifiedExternalOutcomeAttempt(
            context.getTenantId(),
            fileId,
            dispatchPayload.channelCode(),
            jobInstanceId,
            pipelineInstanceId);
        if (authorizedAttempt <= 0) {
          return externalOutcomeUnknown(
              context, fileId, dispatchPayload, snapshot.errorMessage(), false);
        }
        newAttempt = true;
      } else if (FileDispatchStatus.CREATED.name().equals(snapshot.dispatchStatus())) {
        int markedUnknown = fileDispatchRepository.markAttemptFailed(
            context.getTenantId(),
            fileId,
            dispatchPayload.channelCode(),
            TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN,
            "A prior worker left the dispatch record in CREATED; external delivery may have occurred.");
        if (markedUnknown <= 0) {
          Optional<DispatchRecordSnapshot> refreshed = fileDispatchRepository.latestDispatchRecord(
              context.getTenantId(), fileId, dispatchPayload.channelCode());
          if (refreshed.isPresent()) {
            DispatchStageResult recovered =
                resumePersistedDelivery(context, dispatchPayload, refreshed.get());
            if (EmptyChecks.isNotNull(recovered)) {
              return recovered;
            }
          }
        }
        return externalOutcomeUnknown(
            context,
            fileId,
            dispatchPayload,
            "A prior worker left the dispatch record in CREATED; external delivery may have occurred.",
            false);
      } else {
        DispatchStageResult recovered = resumePersistedDelivery(context, dispatchPayload, snapshot);
        if (EmptyChecks.isNotNull(recovered)) {
          return recovered;
        }
        if (fileDispatchRepository.beginAttempt(
                context.getTenantId(), fileId, dispatchPayload.channelCode())
            <= 0) {
          attrs.put(DispatchRuntimeKeys.RETRY_REQUESTED, Boolean.FALSE);
          attrs.remove(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE);
          return DispatchStageResult.failure(
              stage(),
              "DISPATCH_ATTEMPT_STATE_CONFLICT",
              "error.dispatch.deliver.send_failed",
              new Object[] {"dispatch attempt state changed before delivery"},
              "dispatch attempt state changed before delivery",
              ERROR_OBJECT_MAPPER);
        }
      }
    }
    return null;
  }

  private DispatchStageResult resumePersistedDelivery(
      DispatchJobContext context,
      DispatchPayload dispatchPayload,
      DispatchRecordSnapshot snapshot) {
    boolean alreadyAcked = FileDispatchStatus.ACKED.name().equals(snapshot.dispatchStatus());
    if (!alreadyAcked && !FileDispatchStatus.SENT.name().equals(snapshot.dispatchStatus())) {
      return null;
    }
    boolean acknowledged =
        alreadyAcked || FileReceiptStatus.SUCCESS.code().equals(snapshot.receiptStatus());
    boolean pending = FileReceiptStatus.PENDING.code().equals(snapshot.receiptStatus());
    DispatchResult persistedResult = new DispatchResult(
        true,
        snapshot.externalRequestId(),
        snapshot.receiptCode(),
        acknowledged,
        pending,
        "recovered from persisted dispatch state",
        null);
    DispatchInvocationSupport.propagateIdentifiers(context, persistedResult);
    if (alreadyAcked) {
      context
          .getAttributes()
          .put(DispatchRuntimeKeys.RECEIPT_STATUS, FileReceiptStatus.SUCCESS.code());
      context
          .getAttributes()
          .put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.COMPLETE.name());
    }
    context.getAttributes().put(DispatchRuntimeKeys.DISPATCH_RECORD, dispatchPayload);
    return DispatchStageResult.success(stage());
  }

  private DispatchStageResult externalOutcomeUnknown(
      DispatchJobContext context,
      Long fileId,
      DispatchPayload dispatchPayload,
      String message,
      boolean persistIfStillCreated) {
    if (persistIfStillCreated) {
      fileDispatchRepository.markAttemptFailed(
          context.getTenantId(),
          fileId,
          dispatchPayload.channelCode(),
          TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN,
          message);
    }
    context.getAttributes().put(DispatchRuntimeKeys.RETRY_REQUESTED, Boolean.FALSE);
    context.getAttributes().remove(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE);
    return DispatchStageResult.failure(
        stage(),
        TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN,
        "error.dispatch.deliver.send_failed",
        new Object[] {message},
        message,
        ERROR_OBJECT_MAPPER);
  }

  private Map<String, Object> readRawPayload(DispatchJobContext context) {
    Object raw = context.getAttributes().get(DispatchRuntimeKeys.DISPATCH_SECRET_BINDINGS);
    return raw instanceof Map<?, ?>
        ? Map.of(DispatchRuntimeKeys.DISPATCH_SECRET_BINDINGS, raw)
        : Map.of();
  }

  /**
   * ADR-041 Phase1.5:投递成功后从目的端回读字节数,与登记的期望大小对账。开关关 / 无期望大小 / 渠道不支持回读 → 直通(仅告警); 大小不符 → markFailed +
   * 路由 RETRY + 返回失败。默认关闭。
   */
  private DispatchStageResult verifyReadback(
      DispatchJobContext context,
      Long fileId,
      DispatchPayload dispatchPayload,
      Map<String, Object> fileRecord,
      Map<String, Object> channelConfig) {
    if (!DispatchReadbackVerifier.enabled(channelConfig)) {
      return null;
    }
    Long expected = DispatchReadbackVerifier.expectedSizeBytes(fileRecord);
    if (expected == null) {
      log.warn(
          "readback verify enabled but no expected file_size_bytes: tenantId={}, fileId={}",
          context.getTenantId(),
          fileId);
      return null;
    }
    Object traceId = context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID);
    DispatchCommand dispatchCommand = new DispatchCommand(
        context.getTenantId(),
        traceId == null ? null : String.valueOf(traceId),
        fileRecord,
        channelConfig,
        dispatchPayload);
    OptionalLong actual = dispatchChannelGateway.readbackSize(dispatchCommand);
    if (actual.isEmpty()) {
      log.warn(
          "readback verify enabled but channel does not support readback (skipped): "
              + "tenantId={}, fileId={}, channel={}",
          context.getTenantId(),
          fileId,
          dispatchPayload.channelCode());
      return null;
    }
    if (actual.getAsLong() == expected) {
      return null;
    }
    context.getAttributes().put(DispatchRuntimeKeys.RETRY_REQUESTED, Boolean.TRUE);
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE, DispatchStage.RETRY.name());
    fileDispatchRepository.markFailed(
        context.getTenantId(),
        fileId,
        dispatchPayload.channelCode(),
        ERROR_CODE_READBACK_MISMATCH,
        "readback size " + actual.getAsLong() + " != expected " + expected);
    return DispatchStageResult.failure(
        stage(),
        ERROR_CODE_READBACK_MISMATCH,
        "error.dispatch.readback.mismatch",
        new Object[] {expected, actual.getAsLong()},
        "readback size mismatch",
        ERROR_OBJECT_MAPPER);
  }
}
