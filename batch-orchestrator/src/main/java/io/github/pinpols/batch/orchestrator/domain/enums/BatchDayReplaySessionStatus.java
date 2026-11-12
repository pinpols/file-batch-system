package io.github.pinpols.batch.orchestrator.domain.enums;

/**
 * 批次日重放会话 {@code batch_day_replay_session.status} 的取值，与 V110 的 {@code ck_replay_session_status} 一致。
 *
 * <p>{@link #PENDING_APPROVAL} 与 {@link #RUNNING} 是活跃态：同一租户、日历和业务日只允许一个活跃会话。
 */
public enum BatchDayReplaySessionStatus {
  PENDING_APPROVAL,
  RUNNING,
  SUCCEEDED,
  PARTIAL_FAILED,
  CANCELLED;

  public String code() {
    return name();
  }
}
