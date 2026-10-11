package io.github.pinpols.batch.orchestrator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.entity.DispatchSecretBindingEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.orchestrator.mapper.DispatchSecretBindingMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 在 launch 时为本实例引用到的 Dispatch 渠道固定密钥版本号，不复制密钥材料。 */
@Component
@RequiredArgsConstructor
public class DispatchSecretSnapshotResolver {

  static final String SNAPSHOT_KEY = "_dispatchSecretBindings";
  private static final Set<String> CHANNEL_KEYS = Set.of(
      "channelCode",
      "channel_code",
      "targetRef",
      "targetChannelCode",
      "bundleTargetRef",
      "dispatchChannelCode");

  private final DispatchSecretBindingMapper bindingMapper;
  private final WorkflowNodeMapper workflowNodeMapper;

  public Map<String, Object> pin(
      String tenantId,
      String jobType,
      Long workflowDefinitionId,
      Map<String, Object> effectiveParams) {
    Map<String, Object> trustedParams = withoutCallerBindings(effectiveParams);
    if (!JobType.DISPATCH.code().equals(jobType) && !JobType.WORKFLOW.code().equals(jobType)) {
      return trustedParams;
    }
    Set<String> channelCodes = new LinkedHashSet<>();
    collectChannels(trustedParams, channelCodes);
    if (JobType.WORKFLOW.code().equals(jobType) && EmptyChecks.isNotNull(workflowDefinitionId)) {
      List<WorkflowNodeEntity> nodes =
          workflowNodeMapper.selectByWorkflowDefinitionId(workflowDefinitionId);
      for (WorkflowNodeEntity node : nodes) {
        collectChannels(parseObject(node.getNodeParams()), channelCodes);
      }
    }
    if (EmptyChecks.isEmpty(channelCodes)) {
      return trustedParams;
    }

    Map<String, Object> pinned = new LinkedHashMap<>(trustedParams);
    Map<String, Object> bindings = new LinkedHashMap<>();
    for (DispatchSecretBindingEntity binding :
        bindingMapper.selectCurrentByChannels(tenantId, new ArrayList<>(channelCodes))) {
      if (EmptyChecks.isNull(binding.secretVersionId())
          || EmptyChecks.isNull(binding.versionNo())) {
        throw BizException.of(
            ResultCode.CREDENTIAL_REF_UNRESOLVED,
            ResultCode.CREDENTIAL_REF_UNRESOLVED.detailKey(),
            binding.channelCode());
      }
      Map<String, Object> reference = new LinkedHashMap<>();
      reference.put("secretRef", binding.secretRef());
      reference.put("secretVersionId", binding.secretVersionId());
      reference.put("versionNo", binding.versionNo());
      bindings.put(binding.channelCode(), reference);
    }
    if (EmptyChecks.isNotEmpty(bindings)) {
      pinned.put(SNAPSHOT_KEY, bindings);
    }
    return pinned;
  }

  private Map<String, Object> withoutCallerBindings(Map<String, Object> effectiveParams) {
    if (EmptyChecks.isNull(effectiveParams) || !effectiveParams.containsKey(SNAPSHOT_KEY)) {
      return EmptyChecks.isNull(effectiveParams) ? Map.of() : effectiveParams;
    }
    Map<String, Object> sanitized = new LinkedHashMap<>(effectiveParams);
    sanitized.remove(SNAPSHOT_KEY);
    return sanitized;
  }

  private void collectChannels(Object value, Set<String> channels) {
    if (value instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String key = String.valueOf(entry.getKey());
        Object nested = entry.getValue();
        if (CHANNEL_KEYS.contains(key)
            && EmptyChecks.isNotNull(nested)
            && EmptyChecks.isNotBlank(String.valueOf(nested))) {
          channels.add(String.valueOf(nested).trim());
        }
        collectChannels(nested, channels);
      }
      return;
    }
    if (value instanceof Iterable<?> values) {
      for (Object item : values) {
        collectChannels(item, channels);
      }
    }
  }

  private Map<String, Object> parseObject(String json) {
    if (EmptyChecks.isBlank(json)) {
      return Map.of();
    }
    try {
      Object parsed = JsonUtils.fromJson(json, new TypeReference<Object>() {});
      if (!(parsed instanceof Map<?, ?> values)) {
        return Map.of();
      }
      Map<String, Object> result = new LinkedHashMap<>();
      values.forEach((key, value) -> result.put(String.valueOf(key), value));
      return result;
    } catch (RuntimeException exception) {
      return Map.of();
    }
  }
}
