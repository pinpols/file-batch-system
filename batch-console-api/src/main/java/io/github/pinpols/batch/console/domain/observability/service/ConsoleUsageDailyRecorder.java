package io.github.pinpols.batch.console.domain.observability.service;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.domain.observability.mapper.ConsoleUsageDailyMapper;
import io.github.pinpols.batch.console.domain.observability.mapper.ConsoleUsageDailyMapper.DailyUsageRow;
import io.github.pinpols.batch.console.shared.usage.ConsoleUsageRecorder;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 将后端操作审计同步投影为日用量统计；审计是事实源，统计写失败不得影响业务。 */
@Service
@RequiredArgsConstructor
public class ConsoleUsageDailyRecorder implements ConsoleUsageRecorder {

  private static final String SOURCE = "OPERATION_AUDIT";
  private static final String OTHER_METRIC = "operation.other";
  private static final Pattern ACTION_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{1,80}$");

  private final ConsoleUsageDailyMapper mapper;
  private final BatchTimezoneProvider timezoneProvider;

  /** 使用独立事务，避免聚合写失败把外层业务或审计事务标记为回滚。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(String tenantId, String action, boolean success, Instant createdAt) {
    if (!Texts.hasText(tenantId) || createdAt == null) {
      return;
    }
    mapper.setTenantContext(tenantId);
    ZoneId zone = timezoneProvider.defaultZone();
    String metricCode = metricCode(action);
    mapper.insertDaily(new DailyUsageRow(
        createdAt.atZone(zone).toLocalDate(),
        tenantId,
        SOURCE,
        metricCode,
        "",
        "",
        1L,
        success ? 1L : 0L,
        success ? 0L : 1L,
        createdAt.atZone(zone).toOffsetDateTime()));
  }

  private String metricCode(String action) {
    if (!Texts.hasText(action) || !ACTION_PATTERN.matcher(action).matches()) {
      return OTHER_METRIC;
    }
    String normalized = action.toLowerCase(Locale.ROOT);
    String metric = "operation." + normalized;
    return metric.substring(0, Math.min(96, metric.length()));
  }
}
