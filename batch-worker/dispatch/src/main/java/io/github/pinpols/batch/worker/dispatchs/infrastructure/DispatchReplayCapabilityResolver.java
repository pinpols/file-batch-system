package io.github.pinpols.batch.worker.dispatchs.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ReplayOperationType;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchChannelGateway;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.FrozenDispatchChannelResolver;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Resolves replay safety from the exact protected channel configuration carried by the task. */
@Component
public class DispatchReplayCapabilityResolver {

  private final ObjectMapper objectMapper;
  private final FrozenDispatchChannelResolver frozenChannels;
  private final DispatchChannelGateway channelGateway;

  public DispatchReplayCapabilityResolver(
      ObjectMapper objectMapper,
      FrozenDispatchChannelResolver frozenChannels,
      DispatchChannelGateway channelGateway) {
    this.objectMapper = objectMapper;
    this.frozenChannels = frozenChannels;
    this.channelGateway = channelGateway;
  }

  public ReplayCapabilityDeclaration resolve(StepExecutionRequest request) {
    if (request == null || EmptyChecks.isBlank(request.tenantId())) {
      return DispatchReplayCapability.unknown();
    }
    Object payloadValue = request.context() == null
        ? null
        : request.context().get(PipelineRuntimeKeys.PAYLOAD);
    if (!(payloadValue instanceof String rawPayload) || EmptyChecks.isBlank(rawPayload)) {
      return DispatchReplayCapability.unknown();
    }
    try {
      JsonNode payloadNode = objectMapper.readTree(rawPayload);
      boolean payloadIsNotWorkflowRunReplay = EmptyChecks.isNull(payloadNode)
          || !payloadNode.isObject()
          || !ReplayOperationType.WORKFLOW_RUN.code()
              .equals(payloadNode.path("operationType").asText(null));
      if (payloadIsNotWorkflowRunReplay) {
        return DispatchReplayCapability.unknown();
      }
      DispatchPayload dispatchPayload = objectMapper.treeToValue(payloadNode, DispatchPayload.class);
      boolean dispatchPayloadIsInvalid = EmptyChecks.isNull(dispatchPayload)
          || !hasPositiveFileId(dispatchPayload.fileId())
          || EmptyChecks.isBlank(dispatchPayload.channelCode());
      if (dispatchPayloadIsInvalid) {
        return DispatchReplayCapability.unknown();
      }
      Optional<Map<String, Object>> frozenChannel = frozenChannels.resolve(
          request.tenantId(), dispatchPayload.channelCode(), rawPayload);
      if (frozenChannel.isEmpty()) {
        return DispatchReplayCapability.unknown();
      }
      Map<String, Object> channelConfig =
          ChannelConfigMerge.merge(frozenChannel.orElseThrow(), objectMapper);
      Object channelType = channelConfig.get("channel_type");
      if (!(channelType instanceof String type) || !channelGateway.supports(type)) {
        return DispatchReplayCapability.unknown();
      }
      return DispatchReplayCapability.declaration();
    } catch (Exception invalidReplayRequest) {
      return DispatchReplayCapability.unknown();
    }
  }

  private boolean hasPositiveFileId(String fileId) {
    if (EmptyChecks.isBlank(fileId)) {
      return false;
    }
    try {
      return Long.parseLong(fileId) > 0;
    } catch (NumberFormatException invalidFileId) {
      return false;
    }
  }
}
