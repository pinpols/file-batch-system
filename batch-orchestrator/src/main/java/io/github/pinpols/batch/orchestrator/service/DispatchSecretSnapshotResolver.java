package io.github.pinpols.batch.orchestrator.service;

import io.github.pinpols.batch.common.enums.DispatchTaskKeys;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.domain.entity.DispatchSecretBindingEntity;
import io.github.pinpols.batch.orchestrator.mapper.DispatchSecretBindingMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 在 launch 时为本实例引用到的 Dispatch 渠道固定密钥版本号，不复制密钥材料。 */
@Component
@RequiredArgsConstructor
public class DispatchSecretSnapshotResolver {

  private static final Set<String> CHANNEL_KEYS = Set.of(
      "channelCode",
      "channel_code",
      "targetRef",
      "targetChannelCode",
      "bundleTargetRef",
      "dispatchChannelCode");

  private final DispatchSecretBindingMapper bindingMapper;

  public Map<String, Object> pin(
      String tenantId, String jobType, Map<String, Object> effectiveParams) {
    Map<String, Object> trustedParams = withoutCallerBindings(effectiveParams);
    if (!JobType.DISPATCH.code().equals(jobType)) {
      return trustedParams;
    }
    Set<String> channelCodes = new LinkedHashSet<>();
    collectChannels(trustedParams, channelCodes);
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
      pinned.put(DispatchTaskKeys.SECRET_BINDINGS, bindings);
    }
    return pinned;
  }

  private Map<String, Object> withoutCallerBindings(Map<String, Object> effectiveParams) {
    if (EmptyChecks.isNull(effectiveParams)
        || !effectiveParams.containsKey(DispatchTaskKeys.SECRET_BINDINGS)) {
      return EmptyChecks.isNull(effectiveParams) ? Map.of() : effectiveParams;
    }
    Map<String, Object> sanitized = new LinkedHashMap<>(effectiveParams);
    sanitized.remove(DispatchTaskKeys.SECRET_BINDINGS);
    return sanitized;
  }

  private void collectChannels(Object value, Set<String> channels) {
    if (value instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String key = String.valueOf(entry.getKey());
        Object nested = entry.getValue();
        if (CHANNEL_KEYS.contains(key) && EmptyChecks.isNotNull(nested)) {
          String checkedChannel = String.valueOf(nested);
          if (EmptyChecks.isNotBlank(checkedChannel)) {
            String channel = String.valueOf(nested);
            channels.add(channel.trim());
          }
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
}
