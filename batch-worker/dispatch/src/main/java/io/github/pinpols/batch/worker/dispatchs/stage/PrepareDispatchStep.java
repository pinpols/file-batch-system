package io.github.pinpols.batch.worker.dispatchs.stage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.FileReceiptPolicy;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineRunRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.ChannelConfigMerge;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchRuntimeKeys;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Dispatch pipeline 的 PREPARE 阶段：解析 payload，加载文件记录和渠道配置，初始化执行上下文。
 *
 * <p>校验 {@code fileId} 非空后从 {@code file_dispatch_record} 和 {@code file_channel} 加载数据； 将文件绑定到当前
 * pipeline 实例，并将 {@code receipt_policy} 写入上下文供后续步骤判断回执模式。 任一必要数据缺失时直接返回对应的失败码，不进入投递步骤。
 */
@Component
public class PrepareDispatchStep implements DispatchStageStep {

  private final ObjectMapper objectMapper;
  private final FileDispatchRepository fileDispatchRepository;
  private final PlatformPipelineRunRepository pipelineRuns;

  public PrepareDispatchStep(
      ObjectMapper objectMapper,
      FileDispatchRepository fileDispatchRepository,
      PlatformPipelineRunRepository pipelineRuns) {
    this.objectMapper = objectMapper;
    this.fileDispatchRepository = fileDispatchRepository;
    this.pipelineRuns = pipelineRuns;
  }

  @Override
  public DispatchStage stage() {
    return DispatchStage.PREPARE;
  }

  @Override
  public DispatchStageResult execute(DispatchJobContext context) {
    if (context == null
        || !Texts.hasText(context.getTenantId())
        || !Texts.hasText(context.getRawPayload())) {
      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_PREPARE_INVALID",
          "error.dispatch.prepare.invalid",
          new Object[0],
          "tenantId or payload is blank",
          objectMapper);
    }
    try {
      Map<String, Object> attrs = context.getAttributes();
      Object existingPayload = attrs.get(DispatchRuntimeKeys.DISPATCH_PAYLOAD);
      JsonNode rawPayload = readOptionalPayload(context.getRawPayload(), existingPayload);
      DispatchPayload payload;
      if (existingPayload instanceof DispatchPayload dispatchPayload) {
        payload = dispatchPayload;
      } else {
        payload = objectMapper.treeToValue(rawPayload, DispatchPayload.class);
      }
      if (rawPayload != null) {
        JsonNode secretBindings = rawPayload.get(DispatchRuntimeKeys.DISPATCH_SECRET_BINDINGS);
        if (secretBindings != null && secretBindings.isObject()) {
          attrs.put(
              DispatchRuntimeKeys.DISPATCH_SECRET_BINDINGS,
              objectMapper.convertValue(
                  secretBindings, new TypeReference<Map<String, Object>>() {}));
        }
      }
      payload = withStableExternalRequestId(context, payload);
      attrs.put(DispatchRuntimeKeys.DISPATCH_PAYLOAD, payload);
      Long fileId = payload.fileId() == null || payload.fileId().isBlank()
          ? null
          : Long.valueOf(payload.fileId());
      if (fileId == null) {
        return DispatchStageResult.failure(
            stage(),
            "DISPATCH_PREPARE_FILE_MISSING",
            "error.dispatch.prepare.file_missing",
            new Object[0],
            "fileId missing",
            objectMapper);
      }
      Map<String, Object> fileRecord =
          fileDispatchRepository.loadFile(context.getTenantId(), fileId);
      if (fileRecord.isEmpty()) {
        return DispatchStageResult.failure(
            stage(),
            "DISPATCH_PREPARE_FILE_NOT_FOUND",
            "error.dispatch.prepare.file_not_found",
            new Object[0],
            "file record not found",
            objectMapper);
      }
      Map<String, Object> channelRow =
          fileDispatchRepository.loadChannel(context.getTenantId(), payload.channelCode());
      if (channelRow.isEmpty()) {
        return DispatchStageResult.failure(
            stage(),
            "DISPATCH_PREPARE_CHANNEL_NOT_FOUND",
            "error.dispatch.prepare.channel_not_found",
            new Object[0],
            "channel config not found",
            objectMapper);
      }
      Map<String, Object> channelConfig = ChannelConfigMerge.merge(channelRow, objectMapper);
      attrs.put(PipelineRuntimeKeys.FILE_ID, fileId);
      attrs.put(PipelineRuntimeKeys.FILE_RECORD, fileRecord);
      attrs.put(PipelineRuntimeKeys.CHANNEL_CONFIG, channelConfig);
      attrs.put(DispatchRuntimeKeys.RETRY_REQUESTED, Boolean.TRUE.equals(payload.forceRetry()));
      attrs.put(
          DispatchRuntimeKeys.RECEIPT_STATUS,
          channelConfig.getOrDefault("receipt_policy", FileReceiptPolicy.NONE.code()));
      pipelineRuns.bindFileToPipelineInstance(
          PlatformRuntimeValues.toLong(attrs.get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID)),
          fileId);
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(PrepareDispatchStep.class, "catch:Exception", ex);

      return DispatchStageResult.failure(
          stage(),
          "DISPATCH_PREPARE_PARSE_FAILED",
          "error.dispatch.prepare.parse_failed",
          new Object[] {ex.getMessage()},
          ex.getMessage(),
          objectMapper);
    }
    return DispatchStageResult.success(stage());
  }

  private JsonNode readOptionalPayload(String rawPayload, Object existingPayload)
      throws JsonProcessingException {
    try {
      return objectMapper.readTree(rawPayload);
    } catch (JsonProcessingException exception) {
      if (!(existingPayload instanceof DispatchPayload)) {
        throw exception;
      }
      return null;
    }
  }

  private static DispatchPayload withStableExternalRequestId(
      DispatchJobContext context, DispatchPayload payload) {
    if (payload.externalRequestId() != null && !payload.externalRequestId().isBlank()) {
      return payload;
    }
    Object pipelineInstanceId =
        context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID);
    if (pipelineInstanceId == null || payload.fileId() == null || payload.channelCode() == null) {
      return payload;
    }
    String requestIdentity = String.join(
        "|",
        context.getTenantId(),
        String.valueOf(pipelineInstanceId),
        payload.fileId(),
        payload.channelCode());
    String externalRequestId =
        UUID.nameUUIDFromBytes(requestIdentity.getBytes(StandardCharsets.UTF_8)).toString();
    return new DispatchPayload(
        payload.fileId(),
        payload.fileCode(),
        payload.channelCode(),
        payload.dispatchTarget(),
        externalRequestId,
        payload.receiptCode(),
        payload.ackRequired(),
        payload.forceRetry(),
        payload.runMode(),
        payload.metadata());
  }
}
