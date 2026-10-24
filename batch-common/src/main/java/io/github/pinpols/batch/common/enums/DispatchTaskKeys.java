package io.github.pinpols.batch.common.enums;

/** Shared payload keys used by the Orchestrator and Dispatch Worker. */
public final class DispatchTaskKeys {

  public static final String FROZEN_CHANNEL_SNAPSHOTS = "_frozenDispatchChannelsProtected";
  public static final String SECRET_BINDINGS = "_dispatchSecretBindings";

  private DispatchTaskKeys() {}
}
