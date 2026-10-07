package io.github.pinpols.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.transaction.PlatformTransactionManager;

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
@DisplayName("批次日实例维护的早退分支:启动请求上下文缺失时跳过数据库读写,以及补跑启动与迟到接受的判定口径")
class LaunchBatchDayServiceTest {

  @Mock
  private OrchestratorConfigCacheService configCacheService;

  @Mock
  private BatchDayInstanceMapper batchDayInstanceMapper;

  @Mock
  private JobExecutionLogMapper jobExecutionLogMapper;

  @Mock
  private JobInstanceMapper jobInstanceMapper;

  @Mock
  private JobPartitionMapper jobPartitionMapper;

  @Mock
  private JobTaskMapper jobTaskMapper;

  @Mock
  private JobStepInstanceMapper jobStepInstanceMapper;

  @Mock
  private TriggerRequestMapper triggerRequestMapper;

  @Mock
  private BatchTimezoneProvider timezoneProvider;

  @Mock
  private BatchDayTimePolicyResolver timePolicyResolver;

  @Mock
  private BatchDateTimeSupport dateTimeSupport;

  @Mock
  private AlertEventService alertEventService;

  @Mock
  private PlatformTransactionManager transactionManager;

  private LaunchBatchDayService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    OrchestratorJobMappers jobMappers = new OrchestratorJobMappers(
        jobInstanceMapper,
        jobPartitionMapper,
        jobTaskMapper,
        jobStepInstanceMapper,
        triggerRequestMapper);
    service = new LaunchBatchDayService(
        configCacheService,
        batchDayInstanceMapper,
        jobExecutionLogMapper,
        jobMappers,
        timezoneProvider,
        timePolicyResolver,
        dateTimeSupport,
        alertEventService,
        transactionManager);
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
  @DisplayName("启动请求为空时跳过批次日实例维护,不发起数据库查询")
  void shouldSkipBatchDayUpsert_whenLaunchRequestIsNull() {
    service.doUpsertBatchDayInstance(null, jobDef("cal-1"), Map.of(), Instant.now());
    verify(batchDayInstanceMapper, never())
        .selectByTenantCalendarBizDate(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("业务日期为空时跳过批次日实例维护,不发起数据库查询")
  void null_bizDate_short_circuits() {
    LaunchRequest r = req("j1", null, TriggerType.SCHEDULED);
    service.doUpsertBatchDayInstance(r, jobDef("cal-1"), Map.of(), Instant.now());
    verify(batchDayInstanceMapper, never())
        .selectByTenantCalendarBizDate(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("作业定义为空时跳过批次日实例维护,不发起数据库查询")
  void null_jobDefinition_short_circuits() {
    LaunchRequest r = req("j1", LocalDate.of(2026, Month.MAY, 20), TriggerType.SCHEDULED);
    service.doUpsertBatchDayInstance(r, null, Map.of(), Instant.now());
    verify(batchDayInstanceMapper, never())
        .selectByTenantCalendarBizDate(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("日历编码缺失或仅含空白时跳过批次日实例维护,不发起数据库查询")
  void shouldSkipBatchDayUpsert_whenCalendarCodeMissing() {
    LaunchRequest r = req("j1", LocalDate.of(2026, Month.MAY, 20), TriggerType.SCHEDULED);
    service.doUpsertBatchDayInstance(r, jobDef(null), Map.of(), Instant.now());
    verify(batchDayInstanceMapper, never())
        .selectByTenantCalendarBizDate(anyString(), anyString(), any());

    service.doUpsertBatchDayInstance(r, jobDef("  "), Map.of(), Instant.now());
    verify(batchDayInstanceMapper, never())
        .selectByTenantCalendarBizDate(anyString(), anyString(), any());
  }

  // ===== isCatchUpLaunch =====

  @Test
  @DisplayName("仅补跑触发类型判定为补跑启动,其余触发类型与空请求均判定为非补跑")
  void shouldMarkCatchUpLaunchOnly_whenTriggerTypeIsCatchUp() {
    assertThat(service.isCatchUpLaunch(req("j1", LocalDate.now(), TriggerType.CATCH_UP)))
        .isTrue();
    assertThat(service.isCatchUpLaunch(req("j1", LocalDate.now(), TriggerType.SCHEDULED)))
        .isFalse();
    assertThat(service.isCatchUpLaunch(req("j1", LocalDate.now(), TriggerType.MANUAL)))
        .isFalse();
    assertThat(service.isCatchUpLaunch(req("j1", LocalDate.now(), TriggerType.RERUN)))
        .isFalse();
    assertThat(service.isCatchUpLaunch(null)).isFalse();
  }

  // ===== isLateAccepted =====

  @Test
  @DisplayName("迟到标志与接受状态同时命中才判定为迟到接受,其余组合与空入参均判定为非迟到接受")
  void shouldAcceptLateArrivalOnly_whenBothFlagsMatched() {
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
  @DisplayName("接受状态取值大小写不同时仍判定为迟到接受")
  void shouldTreatLateAccepted_whenStatusLetterCaseDiffers() {
    assertThat(
            service.isLateAccepted(Map.of("lateArrival", true, "arrivalStatus", "late_accepted")))
        .isTrue();
  }
}
