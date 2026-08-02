package io.github.pinpols.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/** trigger_request 状态机。持久化和接口传输使用 {@link #code()}。 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum TriggerRequestStatus implements DictEnum {
  ACCEPTED("ACCEPTED", "已接收"),
  LAUNCHED("LAUNCHED", "已派发"),
  REJECTED("REJECTED", "已拒绝"),
  DUPLICATE("DUPLICATE", "重复请求");

  private final String code;
  private final String label;

  public static TriggerRequestStatus fromCodeOrNull(String value) {
    return DictEnum.fromCode(TriggerRequestStatus.class, value);
  }
}
