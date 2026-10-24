package io.github.pinpols.batch.common.enums;

/** Orchestrator 与 Dispatch Worker 共用的载荷键。 */
public final class DispatchTaskKeys {

  public static final String FROZEN_CHANNEL_SNAPSHOTS = "_frozenDispatchChannelsProtected";
  public static final String SECRET_BINDINGS = "_dispatchSecretBindings";

  private DispatchTaskKeys() {}
}
