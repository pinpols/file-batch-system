package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/** trigger_misfire_pending 状态机。持久化和接口传输使用 {@link #code()}。 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum MisfirePendingStatus implements DictEnum {
  PENDING("PENDING", "待审批"),
  APPROVED("APPROVED", "已通过"),
  REJECTED("REJECTED", "已拒绝"),
  EXPIRED("EXPIRED", "已过期");

  private final String code;
  private final String label;
}
