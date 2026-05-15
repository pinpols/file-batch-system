package com.example.batch.orchestrator.service;

import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchDayTimePolicyResolver {

  public static final String DEFAULT_GAP_POLICY = "RUN_AT_NEXT_VALID_TIME";
  // R4-P1-5：cutoff 语义是"到这个本地时刻后日切"。秋令 overlap（本地时间 1:00 出现两次）时：
  // - RUN_ONCE_EARLIER_OFFSET 选夏令时 offset → 实际比标准时配置早 1h 触发 → 误判 late arrival
  // - RUN_ONCE_LATER_OFFSET  选标准时 offset → 与运营配置"06:00 标准时"语义一致
  // 默认改 LATER_OFFSET，保护没显式配置的 tenant；显式配 EARLIER_OFFSET 仍可保留旧语义。
  public static final String DEFAULT_OVERLAP_POLICY = "RUN_ONCE_LATER_OFFSET";

  /**
   * cutoff 是批量日唯一边界,模型不支持双 cutoff;若 calendar 配 {@code RUN_TWICE} 由 cutoff 路径降级到 {@code
   * RUN_ONCE_EARLIER_OFFSET},并显式 warn,便于排障 + 提示运维改配置。 详见
   * docs/design/batch-day-timezone-dst-optimized-design.md §8.3。
   */
  public static final String OVERLAP_POLICY_RUN_TWICE = "RUN_TWICE";

  private final BatchTimezoneProvider timezoneProvider;
  private final CutoffScheduleResolver cutoffScheduleResolver;

  public Instant resolveCutoffAt(BusinessCalendarEntity calendar, LocalDate bizDate) {
    if (calendar == null || bizDate == null) {
      return null;
    }
    ZoneId zoneId = timezoneProvider.resolveOrDefault(calendar.timezone());
    LocalTime baseCutoff =
        calendar.cutoffTime() == null ? LocalTime.of(6, 0) : calendar.cutoffTime();
    // ADR-023 §决策：cutoff_schedule JSONB 优先于单值 cutoff_time
    LocalTime cutoffTime =
        cutoffScheduleResolver.resolveCutoffTime(calendar.cutoffSchedule(), bizDate, baseCutoff);
    String overlapPolicy = effectiveCutoffOverlapPolicy(calendar);
    return resolveLocalBoundary(
        bizDate.plusDays(1),
        cutoffTime,
        zoneId,
        normalize(calendar.dstGapPolicy(), DEFAULT_GAP_POLICY),
        overlapPolicy);
  }

  public String snapshot(BusinessCalendarEntity calendar) {
    String gap = normalize(calendar == null ? null : calendar.dstGapPolicy(), DEFAULT_GAP_POLICY);
    String overlap = effectiveCutoffOverlapPolicy(calendar);
    return "gap=" + gap + ";overlap=" + overlap;
  }

  /**
   * cutoff 路径的实际 overlap 策略:RUN_TWICE 不被支持(cutoff 是批量日唯一边界),按 RUN_ONCE_EARLIER_OFFSET 降级并 warn
   * 一次,snapshot 也写降级后的值,避免 audit 与实际行为不一致。
   */
  private String effectiveCutoffOverlapPolicy(BusinessCalendarEntity calendar) {
    String configured =
        normalize(calendar == null ? null : calendar.dstOverlapPolicy(), DEFAULT_OVERLAP_POLICY);
    if (OVERLAP_POLICY_RUN_TWICE.equals(configured)) {
      log.warn(
          "calendar dst_overlap_policy=RUN_TWICE is not supported for cutoff;"
              + " degrading to {} for tenant={} calendar={}",
          DEFAULT_OVERLAP_POLICY,
          calendar == null ? null : calendar.tenantId(),
          calendar == null ? null : calendar.calendarCode());
      return DEFAULT_OVERLAP_POLICY;
    }
    return configured;
  }

  private Instant resolveLocalBoundary(
      LocalDate date, LocalTime time, ZoneId zoneId, String gapPolicy, String overlapPolicy) {
    LocalDateTime localDateTime = LocalDateTime.of(date, time);
    ZoneRules rules = zoneId.getRules();
    List<ZoneOffset> offsets = rules.getValidOffsets(localDateTime);
    if (offsets.size() == 1) {
      return ZonedDateTime.ofLocal(localDateTime, zoneId, offsets.getFirst()).toInstant();
    }
    if (offsets.size() == 2) {
      ZoneOffset selected =
          "RUN_ONCE_LATER_OFFSET".equals(overlapPolicy) ? offsets.getLast() : offsets.getFirst();
      return ZonedDateTime.ofLocal(localDateTime, zoneId, selected).toInstant();
    }
    ZoneOffsetTransition transition = rules.getTransition(localDateTime);
    if ("SKIP".equals(gapPolicy) || "FAIL_FAST".equals(gapPolicy)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.batch_day.dst_gap");
    }
    if (transition == null) {
      return localDateTime.atZone(zoneId).toInstant();
    }
    return transition.getInstant();
  }

  private String normalize(String value, String defaultValue) {
    return Texts.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : defaultValue;
  }
}
