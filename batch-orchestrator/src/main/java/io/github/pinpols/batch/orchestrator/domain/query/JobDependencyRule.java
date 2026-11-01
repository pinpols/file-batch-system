package io.github.pinpols.batch.orchestrator.domain.query;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Locale;

/** 用于评估就绪状态并持久化延迟启动的不可变依赖边快照。 */
public record JobDependencyRule(String upstreamJobCode, String bizDateAlignment) {

  public static final String SAME_DAY = "SAME_DAY";
  public static final String PREVIOUS_BIZ_DAY = "PREVIOUS_BIZ_DAY";

  public JobDependencyRule {
    if (EmptyChecks.isBlank(bizDateAlignment)) {
      bizDateAlignment = SAME_DAY;
    } else {
      String trimmedAlignment = bizDateAlignment.trim();
      bizDateAlignment = trimmedAlignment.toUpperCase(Locale.ROOT);
    }
  }

  public static JobDependencyRule sameDay(String upstreamJobCode) {
    return new JobDependencyRule(upstreamJobCode, SAME_DAY);
  }
}
