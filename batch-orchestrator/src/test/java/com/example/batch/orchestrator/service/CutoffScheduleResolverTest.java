package com.example.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CutoffScheduleResolverTest {

  private CutoffScheduleResolver resolver;
  private final LocalTime baseDefault = LocalTime.of(6, 0);

  @BeforeEach
  void setUp() {
    resolver = new CutoffScheduleResolver();
  }

  @Test
  void nullScheduleReturnsBaseDefault() {
    assertThat(resolver.resolveCutoffTime(null, LocalDate.of(2026, 5, 4), baseDefault))
        .isEqualTo(baseDefault);
  }

  @Test
  void invalidJsonReturnsBaseDefault() {
    assertThat(resolver.resolveCutoffTime("not-json{[", LocalDate.of(2026, 5, 4), baseDefault))
        .isEqualTo(baseDefault);
  }

  @Test
  void scheduleDefaultUsedWhenNoOverrideMatches() {
    String spec = "{\"default\":\"05:30\",\"overrides\":[]}";
    assertThat(resolver.resolveCutoffTime(spec, LocalDate.of(2026, 5, 4), baseDefault))
        .isEqualTo(LocalTime.of(5, 30));
  }

  @Test
  void exactDateOverrideTakesPriority() {
    String spec =
        "{\"default\":\"05:30\",\"overrides\":["
            + "{\"date\":\"2026-12-24\",\"cutoff\":\"13:00\",\"reason\":\"圣诞夜半天班\"}]}";
    assertThat(resolver.resolveCutoffTime(spec, LocalDate.of(2026, 12, 24), baseDefault))
        .isEqualTo(LocalTime.of(13, 0));
    // 同 schedule 其它日期回到 default
    assertThat(resolver.resolveCutoffTime(spec, LocalDate.of(2026, 12, 25), baseDefault))
        .isEqualTo(LocalTime.of(5, 30));
  }

  @Test
  void weekdayPatternRangeOverrideMatches() {
    String spec =
        "{\"default\":\"06:00\",\"overrides\":["
            + "{\"weekdayPattern\":\"FRIDAY\",\"cutoff\":\"05:30\","
            + "\"from\":\"2026-06-01\",\"to\":\"2026-08-31\"}]}";
    // 2026-06-12 is Friday in window
    assertThat(resolver.resolveCutoffTime(spec, LocalDate.of(2026, 6, 12), baseDefault))
        .isEqualTo(LocalTime.of(5, 30));
    // 2026-09-04 Friday but outside to
    assertThat(resolver.resolveCutoffTime(spec, LocalDate.of(2026, 9, 4), baseDefault))
        .isEqualTo(LocalTime.of(6, 0));
    // 2026-06-12 if treated as Saturday no match — actually 2026-06-12 is Friday, kept
  }

  @Test
  void exactOverrideBeatsWeekdayPattern() {
    String spec =
        "{\"default\":\"06:00\",\"overrides\":["
            + "{\"weekdayPattern\":\"FRIDAY\",\"cutoff\":\"05:30\"},"
            + "{\"date\":\"2026-12-25\",\"cutoff\":\"13:00\"}]}";
    // 2026-12-25 is Friday — exact wins
    assertThat(resolver.resolveCutoffTime(spec, LocalDate.of(2026, 12, 25), baseDefault))
        .isEqualTo(LocalTime.of(13, 0));
  }

  @Test
  void invalidOverrideEntryFallsThrough() {
    String spec =
        "{\"default\":\"06:00\",\"overrides\":[{\"date\":\"BROKEN\",\"cutoff\":\"05:00\"}]}";
    assertThat(resolver.resolveCutoffTime(spec, LocalDate.of(2026, 5, 4), baseDefault))
        .isEqualTo(LocalTime.of(6, 0));
  }

  @Test
  void scheduleWithoutDefaultFallsBackToBase() {
    String spec = "{\"overrides\":[{\"date\":\"2026-01-01\",\"cutoff\":\"08:00\"}]}";
    // Different date, no default → base
    assertThat(resolver.resolveCutoffTime(spec, LocalDate.of(2026, 5, 4), baseDefault))
        .isEqualTo(baseDefault);
  }
}
