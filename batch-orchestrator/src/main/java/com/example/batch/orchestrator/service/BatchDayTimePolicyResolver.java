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
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BatchDayTimePolicyResolver {

  public static final String DEFAULT_GAP_POLICY = "RUN_AT_NEXT_VALID_TIME";
  public static final String DEFAULT_OVERLAP_POLICY = "RUN_ONCE_EARLIER_OFFSET";

  private final BatchTimezoneProvider timezoneProvider;

  public Instant resolveCutoffAt(BusinessCalendarEntity calendar, LocalDate bizDate) {
    if (calendar == null || bizDate == null) {
      return null;
    }
    ZoneId zoneId = timezoneProvider.resolveOrDefault(calendar.timezone());
    LocalTime cutoffTime =
        calendar.cutoffTime() == null ? LocalTime.of(6, 0) : calendar.cutoffTime();
    return resolveLocalBoundary(
        bizDate.plusDays(1),
        cutoffTime,
        zoneId,
        normalize(calendar.dstGapPolicy(), DEFAULT_GAP_POLICY),
        normalize(calendar.dstOverlapPolicy(), DEFAULT_OVERLAP_POLICY));
  }

  public String snapshot(BusinessCalendarEntity calendar) {
    String gap = normalize(calendar == null ? null : calendar.dstGapPolicy(), DEFAULT_GAP_POLICY);
    String overlap =
        normalize(calendar == null ? null : calendar.dstOverlapPolicy(), DEFAULT_OVERLAP_POLICY);
    return "gap=" + gap + ";overlap=" + overlap;
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
