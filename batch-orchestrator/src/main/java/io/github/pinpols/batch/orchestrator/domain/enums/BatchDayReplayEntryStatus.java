package io.github.pinpols.batch.orchestrator.domain.enums;

/** 批次日重放条目 {@code batch_day_replay_entry.status} 的取值，与 V110 的 {@code ck_replay_entry_status} 一致。 */
public enum BatchDayReplayEntryStatus {
  PENDING,
  RUNNING,
  SUCCEEDED,
  FAILED,
  SKIPPED;

  public String code() {
    return name();
  }
}
