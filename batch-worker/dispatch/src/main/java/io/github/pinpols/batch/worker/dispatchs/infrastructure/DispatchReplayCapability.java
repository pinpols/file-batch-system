package io.github.pinpols.batch.worker.dispatchs.infrastructure;

import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.SystemPipelineCatalog;
import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchWorkerType;
import java.util.List;

/** Replay contract backed by the durable tenant/file/channel dispatch record state machine. */
final class DispatchReplayCapability {

  private static final String EFFECT_CATEGORY = "FILE_CHANNEL_DELIVERY";
  private static final String KEY_SCOPE = "tenantId:fileId:channelCode";
  private static final String RECOVERY_PROTOCOL = "file-dispatch-record-state-machine-v1";

  private DispatchReplayCapability() {}

  static ReplayCapabilityDeclaration declaration() {
    int semanticVersion = SystemPipelineCatalog.forJobType(JobType.DISPATCH)
        .orElseThrow(() -> new IllegalStateException("DISPATCH system pipeline is missing"))
        .semanticVersion();
    return new ReplayCapabilityDeclaration(
        DispatchWorkerType.DISPATCH,
        Integer.toString(semanticVersion),
        List.of(EFFECT_CATEGORY),
        IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT,
        KEY_SCOPE,
        RECOVERY_PROTOCOL,
        ReplayCapabilityDeclaration.CURRENT_CONTRACT_VERSION);
  }

  static ReplayCapabilityDeclaration unknown() {
    return ReplayCapabilityDeclaration.unknown(DispatchWorkerType.DISPATCH);
  }
}
