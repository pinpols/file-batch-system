package com.example.batch.worker.dispatchs.stage;

import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.dispatchs.domain.DispatchJobContext;
import com.example.batch.worker.dispatchs.domain.DispatchPayload;
import com.example.batch.worker.dispatchs.domain.DispatchStage;
import com.example.batch.worker.dispatchs.domain.DispatchStageResult;
import com.example.batch.worker.dispatchs.infrastructure.ChannelConfigMerge;
import com.example.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
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
  private final PlatformFileRuntimeRepository runtimeRepository;

  public PrepareDispatchStep(
      ObjectMapper objectMapper,
      FileDispatchRepository fileDispatchRepository,
      PlatformFileRuntimeRepository runtimeRepository) {
    this.objectMapper = objectMapper;
    this.fileDispatchRepository = fileDispatchRepository;
    this.runtimeRepository = runtimeRepository;
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
          stage(), "DISPATCH_PREPARE_INVALID", "tenantId or payload is blank");
    }
    try {
      DispatchPayload payload =
          context.getAttributes().get("dispatchPayload") instanceof DispatchPayload dispatchPayload
              ? dispatchPayload
              : objectMapper.readValue(context.getRawPayload(), DispatchPayload.class);
      context.getAttributes().put("dispatchPayload", payload);
      Long fileId =
          payload.fileId() == null || payload.fileId().isBlank()
              ? null
              : Long.valueOf(payload.fileId());
      if (fileId == null) {
        return DispatchStageResult.failure(
            stage(), "DISPATCH_PREPARE_FILE_MISSING", "fileId missing");
      }
      Map<String, Object> fileRecord =
          fileDispatchRepository.loadFile(context.getTenantId(), fileId);
      if (fileRecord.isEmpty()) {
        return DispatchStageResult.failure(
            stage(), "DISPATCH_PREPARE_FILE_NOT_FOUND", "file record not found");
      }
      Map<String, Object> channelRow =
          fileDispatchRepository.loadChannel(context.getTenantId(), payload.channelCode());
      if (channelRow.isEmpty()) {
        return DispatchStageResult.failure(
            stage(), "DISPATCH_PREPARE_CHANNEL_NOT_FOUND", "channel config not found");
      }
      Map<String, Object> channelConfig = ChannelConfigMerge.merge(channelRow, objectMapper);
      context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, fileId);
      context.getAttributes().put(PipelineRuntimeKeys.FILE_RECORD, fileRecord);
      context.getAttributes().put(PipelineRuntimeKeys.CHANNEL_CONFIG, channelConfig);
      context.getAttributes().put("retryRequested", Boolean.TRUE.equals(payload.forceRetry()));
      context
          .getAttributes()
          .put("receiptStatus", channelConfig.getOrDefault("receipt_policy", "NONE"));
      runtimeRepository.bindFileToPipelineInstance(
          runtimeRepository.toLong(
              context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID)),
          fileId);
    } catch (Exception ex) {
      return DispatchStageResult.failure(stage(), "DISPATCH_PREPARE_PARSE_FAILED", ex.getMessage());
    }
    return DispatchStageResult.success(stage());
  }
}
