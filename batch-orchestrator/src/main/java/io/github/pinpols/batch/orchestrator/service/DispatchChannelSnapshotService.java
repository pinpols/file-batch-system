package io.github.pinpols.batch.orchestrator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.dto.FrozenDispatchChannelSnapshot;
import io.github.pinpols.batch.common.enums.ConfigVersionPolicy;
import io.github.pinpols.batch.common.enums.DispatchTaskKeys;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.plan.BundlePlanParams;
import io.github.pinpols.batch.orchestrator.mapper.DispatchChannelSnapshotMapper;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 按 Job 固定分发通道行，防止后续执行静默读取更新后的配置。 */
@Service
@RequiredArgsConstructor
public class DispatchChannelSnapshotService {

  private static final Set<String> CHANNEL_KEYS = Set.of(
      "channelCode",
      "channel_code",
      "targetRef",
      "targetChannelCode",
      "bundleTargetRef",
      "dispatchChannelCode");

  private final DispatchChannelSnapshotMapper channelSnapshots;
  private final SecretPayloadProtector secretPayloadProtector;

  public Map<String, Object> pin(
      String tenantId, String jobType, Map<String, Object> effectiveParams) {
    Map<String, Object> pinned = withoutCallerSnapshots(effectiveParams);
    if (!JobType.DISPATCH.code().equals(jobType)
        && !JobType.BUNDLE_DISPATCH.code().equals(jobType)) {
      return pinned;
    }
    boolean bundleDispatch = JobType.BUNDLE_DISPATCH.code().equals(jobType);
    Set<String> requestedChannels =
        bundleDispatch ? BundlePlanParams.dispatchChannelCodes(pinned) : channelCodes(pinned);
    String selectedChannel =
        bundleDispatch ? null : DispatchChannelSelection.explicitChannel(pinned);
    if (EmptyChecks.isNotNull(selectedChannel)) {
      requestedChannels.add(selectedChannel);
    }
    if (EmptyChecks.isEmpty(Objects.requireNonNull(requestedChannels))) {
      return pinned;
    }
    Map<String, String> snapshots = captureLatest(tenantId, requestedChannels);
    if (EmptyChecks.isNotNull(selectedChannel) && !snapshots.containsKey(selectedChannel)) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.file_channel.not_found", selectedChannel);
    }
    if (bundleDispatch && snapshots.size() != requestedChannels.size()) {
      for (String channelCode : requestedChannels) {
        if (!snapshots.containsKey(channelCode)) {
          throw BizException.of(ResultCode.NOT_FOUND, "error.file_channel.not_found", channelCode);
        }
      }
    }
    if (EmptyChecks.isNotEmpty(snapshots)) {
      pinned.put(FrozenDispatchChannelSnapshot.COLLECTION_KEY, snapshots);
    }
    return pinned;
  }

  public ReplayDispatchSelection captureForWorkflowReplay(ReplayDispatchCaptureRequest request) {
    if (EmptyChecks.isNull(request)) {
      throw invalidSnapshot();
    }
    String tenantId = request.tenantId();
    String jobType = request.jobType();
    Map<String, Object> effectiveParams = request.effectiveParams();
    ConfigVersionPolicy policy = request.policy();
    Map<String, Object> originalEffectiveParams = request.originalEffectiveParams();
    Map<String, Object> selectedSecretBindings = request.selectedSecretBindings();
    if (!JobType.DISPATCH.code().equals(jobType)) {
      return new ReplayDispatchSelection(Map.of(), Map.of(), null);
    }
    if (policy == ConfigVersionPolicy.USE_SPECIFIED_VERSION) {
      throw unavailableConfiguration();
    }
    Map<String, Object> selectedParams = policy == ConfigVersionPolicy.USE_ORIGINAL_CONFIG
        ? EmptyChecks.isNull(originalEffectiveParams) ? Map.of() : originalEffectiveParams
        : effectiveParams;
    Map<String, Object> selectedParamsWithoutCallerSnapshots =
        withoutCallerSnapshots(selectedParams);
    Set<String> requestedChannels = channelCodes(selectedParamsWithoutCallerSnapshots);
    String selectedChannel =
        DispatchChannelSelection.explicitChannel(selectedParamsWithoutCallerSnapshots);
    if (EmptyChecks.isNotNull(selectedChannel)) {
      requestedChannels.add(selectedChannel);
    }
    if (EmptyChecks.isEmpty(Objects.requireNonNull(requestedChannels))) {
      throw unavailableConfiguration();
    }
    Set<?> originalChannels = Set.of();
    if (policy == ConfigVersionPolicy.USE_ORIGINAL_CONFIG) {
      Object rawSnapshots =
          originalEffectiveParams.get(FrozenDispatchChannelSnapshot.COLLECTION_KEY);
      if (!(rawSnapshots instanceof Map<?, ?> snapshots)
          || !replayChannelsMatch(requestedChannels, selectedChannel, snapshots.keySet())) {
        throw unavailableConfiguration();
      }
      originalChannels = snapshots.keySet();
    }
    Map<String, String> protectedSnapshots = new LinkedHashMap<>();
    Map<String, FrozenDispatchChannelSnapshot> decodedSnapshots = new LinkedHashMap<>();
    Map<String, Object> secretBindings = new LinkedHashMap<>(copyStringMap(selectedSecretBindings));
    for (String channelCode : requestedChannels) {
      if (policy == ConfigVersionPolicy.USE_ORIGINAL_CONFIG
          && !originalChannels.contains(channelCode)) {
        continue;
      }
      String protectedSnapshot = policy == ConfigVersionPolicy.USE_ORIGINAL_CONFIG
          ? originalSnapshot(tenantId, channelCode, originalEffectiveParams)
          : captureLatestOne(tenantId, channelCode);
      if (EmptyChecks.isBlank(protectedSnapshot)
          && EmptyChecks.isNotNull(selectedChannel)
          && !selectedChannel.equals(channelCode)) {
        continue;
      }
      FrozenDispatchChannelSnapshot snapshot = reveal(protectedSnapshot, tenantId, channelCode);
      if (!snapshot.enabled()) {
        if (EmptyChecks.isNotNull(selectedChannel) && !selectedChannel.equals(channelCode)) {
          if (secretBindings.containsKey(channelCode)) {
            Object binding = secretBindings.get(channelCode);
            if (EmptyChecks.isNull(binding)) {
              throw invalidSnapshot();
            }
            validateSecretBindings(Map.of(channelCode, snapshot), Map.of(channelCode, binding));
            secretBindings.remove(channelCode);
          }
          continue;
        }
        throw unavailableConfiguration();
      }
      Map<String, Object> configJson = map(snapshot.configuration().get("config_json"));
      Object secretRef = configJson.get("secret_ref");
      if (EmptyChecks.isNotNull(selectedChannel)
          && !selectedChannel.equals(channelCode)
          && secretRef instanceof String text
          && !EmptyChecks.isBlank(text)
          && !selectedSecretBindings.containsKey(channelCode)) {
        continue;
      }
      protectedSnapshots.put(channelCode, protectedSnapshot);
      decodedSnapshots.put(channelCode, snapshot);
    }
    validateSecretBindings(decodedSnapshots, secretBindings);
    return new ReplayDispatchSelection(
        Map.copyOf(protectedSnapshots),
        Collections.unmodifiableMap(secretBindings),
        JobType.DISPATCH.code());
  }

  public Map<String, Object> pinWorkflowReplay(
      String tenantId,
      String jobType,
      Map<String, Object> effectiveParams,
      Map<String, String> protectedSnapshots,
      Map<String, Object> secretBindings) {
    Map<String, Object> pinned = withoutCallerSnapshots(effectiveParams);
    pinned.remove(DispatchTaskKeys.SECRET_BINDINGS);
    Map<String, String> approvedSnapshots =
        EmptyChecks.isNull(protectedSnapshots) ? Map.of() : Map.copyOf(protectedSnapshots);
    Map<String, Object> approvedBindings = copyStringMap(secretBindings);
    if (!JobType.DISPATCH.code().equals(jobType)) {
      if (!EmptyChecks.isEmpty(Objects.requireNonNull(approvedSnapshots))
          || !EmptyChecks.isEmpty(Objects.requireNonNull(approvedBindings))) {
        throw invalidSnapshot();
      }
      return pinned;
    }

    Set<String> requestedChannels = channelCodes(pinned);
    String selectedChannel = DispatchChannelSelection.explicitChannel(pinned);
    if (EmptyChecks.isNotNull(selectedChannel)) {
      requestedChannels.add(selectedChannel);
    }
    if (EmptyChecks.isEmpty(Objects.requireNonNull(requestedChannels))
        || !replayChannelsMatch(requestedChannels, selectedChannel, approvedSnapshots.keySet())) {
      throw invalidSnapshot();
    }
    Map<String, FrozenDispatchChannelSnapshot> decodedSnapshots = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : approvedSnapshots.entrySet()) {
      String channelCode = entry.getKey();
      FrozenDispatchChannelSnapshot snapshot = reveal(entry.getValue(), tenantId, entry.getKey());
      decodedSnapshots.put(channelCode, snapshot);
    }
    validateSecretBindings(decodedSnapshots, approvedBindings);
    pinned.put(FrozenDispatchChannelSnapshot.COLLECTION_KEY, approvedSnapshots);
    if (!EmptyChecks.isEmpty(Objects.requireNonNull(approvedBindings))) {
      pinned.put(DispatchTaskKeys.SECRET_BINDINGS, approvedBindings);
    }
    return pinned;
  }

  private boolean replayChannelsMatch(Set<String> candidates, String selected, Set<?> snapshots) {
    if (EmptyChecks.isNull(selected)) {
      return candidates.equals(snapshots);
    }
    return snapshots.contains(selected) && candidates.containsAll(snapshots);
  }

  private Map<String, Object> withoutCallerSnapshots(Map<String, Object> effectiveParams) {
    Map<String, Object> pinned = EmptyChecks.isNull(effectiveParams)
        ? new LinkedHashMap<>()
        : new LinkedHashMap<>(effectiveParams);
    pinned.remove(FrozenDispatchChannelSnapshot.COLLECTION_KEY);
    return pinned;
  }

  private Map<String, String> captureLatest(String tenantId, Set<String> channelCodes) {
    Map<String, String> snapshots = new LinkedHashMap<>();
    for (String channelCode : channelCodes) {
      String protectedSnapshot = captureLatestOne(tenantId, channelCode);
      if (EmptyChecks.isNotBlank(protectedSnapshot)) {
        snapshots.put(channelCode, protectedSnapshot);
      }
    }
    return snapshots;
  }

  private String captureLatestOne(String tenantId, String channelCode) {
    String snapshotJson = channelSnapshots.selectCurrentChannelSnapshotJson(tenantId, channelCode);
    if (EmptyChecks.isBlank(snapshotJson)) {
      return null;
    }
    Map<String, Object> row = readMap(snapshotJson);
    FrozenDispatchChannelSnapshot snapshot = FrozenDispatchChannelSnapshot.capture(row);
    snapshot.requireIdentity(tenantId, channelCode);
    String serializedSnapshot = JsonUtils.toJson(snapshot);
    return secretPayloadProtector.protect(serializedSnapshot);
  }

  private String originalSnapshot(
      String tenantId, String channelCode, Map<String, Object> originalEffectiveParams) {
    Map<String, Object> original =
        EmptyChecks.isNull(originalEffectiveParams) ? Map.of() : originalEffectiveParams;
    Object rawSnapshots = original.get(FrozenDispatchChannelSnapshot.COLLECTION_KEY);
    if (!(rawSnapshots instanceof Map<?, ?> snapshots)) {
      throw unavailableConfiguration();
    }
    Object rawSnapshot = snapshots.get(channelCode);
    if (!(rawSnapshot instanceof String protectedSnapshot)
        || EmptyChecks.isBlank(protectedSnapshot)) {
      throw unavailableConfiguration();
    }
    return protectedSnapshot;
  }

  private FrozenDispatchChannelSnapshot reveal(
      String protectedSnapshot, String tenantId, String channelCode) {
    try {
      if (EmptyChecks.isBlank(protectedSnapshot)) {
        throw invalidSnapshot();
      }
      String plaintext = secretPayloadProtector.revealEncrypted(protectedSnapshot);
      FrozenDispatchChannelSnapshot snapshot =
          JsonUtils.fromJsonStrict(plaintext, FrozenDispatchChannelSnapshot.class);
      if (EmptyChecks.isNull(snapshot)) {
        throw invalidSnapshot();
      }
      snapshot.requireIdentity(tenantId, channelCode);
      return snapshot;
    } catch (RuntimeException invalidProtectedSnapshot) {
      throw invalidSnapshot();
    }
  }

  private void validateSecretBindings(
      Map<String, FrozenDispatchChannelSnapshot> snapshots, Map<String, Object> secretBindings) {
    if (!snapshots.keySet().containsAll(secretBindings.keySet())) {
      throw invalidSnapshot();
    }
    for (Map.Entry<String, FrozenDispatchChannelSnapshot> entry : snapshots.entrySet()) {
      Map<String, Object> configuration = entry.getValue().configuration();
      Map<String, Object> configJson = map(configuration.get("config_json"));
      Object rawSecretRef = configJson.get("secret_ref");
      Object rawBinding = secretBindings.get(entry.getKey());
      if (rawSecretRef instanceof String secretRef && !EmptyChecks.isBlank(secretRef)) {
        Map<String, Object> binding = map(rawBinding);
        if (!secretRef.equals(binding.get("secretRef"))) {
          throw invalidSnapshot();
        }
        if (positiveLongOrZero(binding.get("secretVersionId")) <= 0) {
          throw invalidSnapshot();
        }
        if (positiveLongOrZero(binding.get("versionNo")) <= 0) {
          throw invalidSnapshot();
        }
      } else if (EmptyChecks.isNotNull(rawBinding)) {
        throw invalidSnapshot();
      }
    }
  }

  private Set<String> channelCodes(Map<String, Object> parameters) {
    Set<String> channels = new LinkedHashSet<>();
    collectChannels(parameters, channels);
    return channels;
  }

  private void collectChannels(Object value, Set<String> channels) {
    if (value instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String key = String.valueOf(entry.getKey());
        Object nested = entry.getValue();
        if (CHANNEL_KEYS.contains(key)
            && nested instanceof String channel
            && !EmptyChecks.isBlank(channel)) {
          channels.add(channel.trim());
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

  private Map<String, Object> readMap(String json) {
    try {
      Map<String, Object> parsed = JsonUtils.fromJson(json, new TypeReference<>() {});
      if (EmptyChecks.isNull(parsed)) {
        throw invalidSnapshot();
      }
      return copyStringMap(parsed);
    } catch (RuntimeException invalidJson) {
      throw invalidSnapshot();
    }
  }

  private Map<String, Object> copyStringMap(Map<?, ?> source) {
    if (EmptyChecks.isNull(source)) {
      return Map.of();
    }
    Map<String, Object> copy = new LinkedHashMap<>();
    source.forEach((key, value) -> {
      if (!(key instanceof String text)) {
        throw invalidSnapshot();
      }
      copy.put(text, value);
    });
    return Collections.unmodifiableMap(copy);
  }

  private Map<String, Object> map(Object value) {
    if (!(value instanceof Map<?, ?> raw)) {
      return Map.of();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    raw.forEach((key, item) -> result.put(String.valueOf(key), item));
    return result;
  }

  private long positiveLongOrZero(Object value) {
    try {
      String numericText = String.valueOf(value);
      BigDecimal numericValue = new BigDecimal(numericText);
      return numericValue.longValueExact();
    } catch (ArithmeticException | NumberFormatException invalidNumber) {
      return 0;
    }
  }

  private BizException unavailableConfiguration() {
    return BizException.of(
        ResultCode.STATE_CONFLICT, "error.workflow.replay_source_snapshot_invalid");
  }

  private BizException invalidSnapshot() {
    return BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.execution_snapshot_invalid");
  }

  public record ReplayDispatchSelection(
      Map<String, String> protectedSnapshots,
      Map<String, Object> secretBindings,
      String pluginId) {}

  public record ReplayDispatchCaptureRequest(
      String tenantId,
      String jobType,
      Map<String, Object> effectiveParams,
      ConfigVersionPolicy policy,
      Map<String, Object> originalEffectiveParams,
      Map<String, Object> selectedSecretBindings) {

    public ReplayDispatchCaptureRequest {
      boolean dispatchCaptureContextIncomplete = EmptyChecks.isBlank(tenantId)
          || EmptyChecks.isBlank(jobType)
          || EmptyChecks.isNull(effectiveParams)
          || EmptyChecks.isNull(policy);
      if (dispatchCaptureContextIncomplete) {
        throw new IllegalArgumentException("invalid dispatch replay capture request");
      }
      effectiveParams = Collections.unmodifiableMap(new LinkedHashMap<>(effectiveParams));
      originalEffectiveParams = EmptyChecks.isNull(originalEffectiveParams)
          ? Map.of()
          : Collections.unmodifiableMap(new LinkedHashMap<>(originalEffectiveParams));
      selectedSecretBindings = EmptyChecks.isNull(selectedSecretBindings)
          ? Map.of()
          : Collections.unmodifiableMap(new LinkedHashMap<>(selectedSecretBindings));
    }
  }
}
