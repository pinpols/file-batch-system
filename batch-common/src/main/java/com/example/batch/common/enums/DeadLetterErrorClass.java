package com.example.batch.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * dead_letter_task.error_class 字典 (V90)。
 *
 * <ul>
 *   <li>{@link #BUSINESS} — 硬错，自动重放不会自愈（如文件缺失、渠道未配），仅人工 {@code /internal/dead-letters/{id}/replay}
 *       重放
 *   <li>{@link #SYSTEM} — 软错（瞬态/可恢复），由 {@code DeadLetterAutoRetryScheduler} 按指数退避自动重放， 超过
 *       max_replay_count 后转 GIVE_UP
 * </ul>
 */
@RequiredArgsConstructor
@Accessors(fluent = true)
@Getter
public enum DeadLetterErrorClass implements DictEnum {
  BUSINESS("BUSINESS", "业务错误"),
  SYSTEM("SYSTEM", "系统错误");

  private final String code;
  private final String label;

  public static DeadLetterErrorClass from(String value) {
    if (value == null || value.isBlank()) {
      return SYSTEM;
    }
    DeadLetterErrorClass hit = DictEnum.fromCode(DeadLetterErrorClass.class, value.trim());
    return hit == null ? SYSTEM : hit;
  }
}
