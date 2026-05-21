package com.example.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.application.service.governance.AlertEventService;
import com.example.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.mapper.BatchDayInstanceMapper;
import com.example.batch.orchestrator.mapper.JobExecutionLogMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobStepInstanceMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.TriggerRequestMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 守护 LaunchBatchDayService 的 doUpsertBatchDayInstance 早退分支与判定逻辑:
 *
 * <ul>
 *   <li>request null / bizDate null / jobDefinition null → 不读 DB
 *   <li>jobDefinition.calendarCode 缺失 → 不读 DB,跳过 batch_day 维护
 *   <li>isLateAccepted: 必须 lateArrival=true 且 arrivalStatus=LATE_ACCEPTED
 *   <li>isCatchUpLaunch: 仅 TriggerType.CATCH_UP 视为补跑
 * </ul>
 *
 * <p>upsert 主流程(insert / update / 审计 / SLA 计算) 已被 DefaultLaunchServiceTest 4 个集成测试覆盖。
 */
class LaunchBatchDayServiceTest {

  @Mock private OrchestratorConfigCacheService configCacheService;
  @Mock private BatchDayInstanceMapper batchDayInstanceMapper;
  @Mock private JobExecutionLogMapper jobExecutionLogMapper;
  @Mock private JobInstanceMapper jobInstanceMapper;
  @Mock private JobPartitionMapper jobPartitionMapper;
  @Mock private JobTaskMapper jobTaskMapper;
  @Mock private JobStepInstanceMapper jobStepInstanceMapper;
  @Mock private TriggerRequestMapper triggerRequestMapper;
  @Mock private BatchTimezoneProvider timezoneProvider;
  @Mock private BatchDayTimePolicyResolver timePolicyResolver;
  @Mock private ObjectProvider<LaunchBatchDayService> selfProvider;
  @Mock private BatchDateTimeSupport dateTimeSupport;
  @Mock private AlertEventService alertEventService;

  private LaunchBatchDayService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    OrchestratorJobMappers jobMappers =
        new OrchestratorJobMappers(
            jobInstanceMapper,
            jobPartitionMapper,
            jobTaskMapper,
            jobStepInstanceMapper,
            triggerRequestMapper);
    service =
        new LaunchBatchDayService(
            configCacheService,
            batchDayInstanceMapper,
            jobExecutionLogMapper,
            jobMappers,
            timezoneProvider,
            timePolicyResolver,
            selfProvider,
            dateTimeSupport,
            alertEventService);
    when(dateTimeSupport.nowInstant()).thenReturn(Instant.parse("2026-05-20T10:00:00Z"));
  }

  private LaunchRequest req(String jobCode, LocalDate bizDate, TriggerType type) {
    return new LaunchRequest("ta", jobCode, bizDate, type, "req-1", "trace", Map.of());
  }

  private JobDefinitionEntity jobDef(String calendarCode) {
    return JobDefinitionEntity.builder()
        .id(1L)
        .tenantId("ta")
        .jobCode("j1")
        .calendarCode(calendarCode)
        .build();
  }

  // ===== isMissingLaunchContext 早退 =====

  @Test
  @DisplayName("request=null → 早退,不读 batch_day")
  void nullRequestShortCircuits() {
    service.doUpsertBatchDayInstance(null, jobDef("cal-1"), Map.of(), Instant.now());
    verify(batchDayInstanceMapper, never())
        .selectByTenantCalendarBizDate(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("bizDate=null → 早退")
  void null_bizDate_short_circuits() {
    LaunchRequest r = req("j1", null, TriggerType.SCHEDULED);
    service.doUpsertBatchDayInstance(r, jobDef("cal-1"), Map.of(), Instant.now());
    verify(batchDayInstanceMapper, never())
        .selectByTenantCalendarBizDate(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("jobDefinition=null → 早退")
  void null_jobDefinition_short_circuits() {
    LaunchRequest r = req("j1", LocalDate.of(2026, 5, 20), TriggerType.SCHEDULED);
    service.doUpsertBatchDayInstance(r, null, Map.of(), Instant.now());
    verify(batchDayInstanceMapper, never())
        .selectByTenantCalendarBizDate(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("calendarCode 缺失 → 早退,不维护 batch_day")
  void missingCalendarCodeShortCircuits() {
    LaunchRequest r = req("j1", LocalDate.of(2026, 5, 20), TriggerType.SCHEDULED);
    service.doUpsertBatchDayInstance(r, jobDef(null), Map.of(), Instant.now());
    verify(batchDayInstanceMapper, never())
        .selectByTenantCalendarBizDate(anyString(), anyString(), any());

    service.doUpsertBatchDayInstance(r, jobDef("  "), Map.of(), Instant.now());
    verify(batchDayInstanceMapper, never())
        .selectByTenantCalendarBizDate(anyString(), anyString(), any());
  }

  // ===== isCatchUpLaunch =====

  @Test
  @DisplayName("isCatchUpLaunch: 仅 CATCH_UP 视为补跑;其他 trigger 返 false")
  void catchUpLaunchOnlyForCatchUpTrigger() {
    assertThat(service.isCatchUpLaunch(req("j1", LocalDate.now(), TriggerType.CATCH_UP))).isTrue();
    assertThat(service.isCatchUpLaunch(req("j1", LocalDate.now(), TriggerType.SCHEDULED)))
        .isFalse();
    assertThat(service.isCatchUpLaunch(req("j1", LocalDate.now(), TriggerType.MANUAL))).isFalse();
    assertThat(service.isCatchUpLaunch(req("j1", LocalDate.now(), TriggerType.RERUN))).isFalse();
    assertThat(service.isCatchUpLaunch(null)).isFalse();
  }

  // ===== isLateAccepted =====

  @Test
  @DisplayName("isLateAccepted: lateArrival=true 且 arrivalStatus=LATE_ACCEPTED 才算迟到接受")
  void lateAcceptedRequiresBothFlags() {
    assertThat(
            service.isLateAccepted(Map.of("lateArrival", true, "arrivalStatus", "LATE_ACCEPTED")))
        .isTrue();
    // 仅 lateArrival 不够
    assertThat(service.isLateAccepted(Map.of("lateArrival", true))).isFalse();
    // arrivalStatus 是其他值
    assertThat(service.isLateAccepted(Map.of("lateArrival", true, "arrivalStatus", "ON_TIME")))
        .isFalse();
    // lateArrival=false
    assertThat(
            service.isLateAccepted(Map.of("lateArrival", false, "arrivalStatus", "LATE_ACCEPTED")))
        .isFalse();
    // null 入参
    assertThat(service.isLateAccepted(null)).isFalse();
    assertThat(service.isLateAccepted(Map.of())).isFalse();
  }

  @Test
  @DisplayName("isLateAccepted: arrivalStatus 大小写不敏感")
  void lateAcceptedCaseInsensitive() {
    assertThat(
            service.isLateAccepted(Map.of("lateArrival", true, "arrivalStatus", "late_accepted")))
        .isTrue();
  }
}
