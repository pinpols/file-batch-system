package io.github.pinpols.batch.orchestrator.application.service.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.service.asset.AssetPartitionService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Month;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("结果版本写入器: 首次生效, 重跑策略与质量门禁阻断口径")
class ResultVersionWriterTest {

  private ResultVersionMapper mapper;
  private AssetPartitionService assetPartitionService;
  private ResultVersionWriter writer;

  private io.github.pinpols.batch.orchestrator.application.service.dataquality
          .DataQualityCheckExecutor
      dqExecutor;

  @BeforeEach
  void setUp() {
    mapper = mock(ResultVersionMapper.class);
    assetPartitionService = mock(AssetPartitionService.class);
    BatchDateTimeSupport dateTimeSupport = new BatchDateTimeSupport(
        Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
    dqExecutor = mock(
        io.github.pinpols.batch.orchestrator.application.service.dataquality
            .DataQualityCheckExecutor.class);
    @SuppressWarnings("unchecked")
    org.springframework.beans.factory.ObjectProvider<
            io.github.pinpols.batch.orchestrator.application.service.dataquality
                .DataQualityCheckExecutor>
        dqProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
    when(dqProvider.getIfAvailable()).thenReturn(dqExecutor);
    when(dqExecutor.execute(any(), anyString()))
        .thenReturn(io.github.pinpols.batch.orchestrator.application.service.dataquality
            .DataQualityGateOutcome.noRules());
    when(mapper.insertReturning(any())).thenAnswer(invocation -> invocation.getArgument(0));
    writer = new ResultVersionWriter(mapper, dateTimeSupport, assetPartitionService, dqProvider);
  }

  @Test
  @DisplayName("首次成功作业写入第一个生效版本, 状态为生效并物化分区")
  void shouldWriteFirstVersionEffective_whenJobSucceeds() {
    JobInstanceEntity instance =
        success("t1", 100L, "DAILY_PNL", LocalDate.of(2026, Month.MAY, 4), null);
    when(mapper.selectByJobInstanceId("t1", 100L)).thenReturn(null);
    when(mapper.selectMaxVersionNo("t1", "job:DAILY_PNL:2026-05-04")).thenReturn(null);

    writer.writeOnTerminal(instance, Map.of("recordCount", 42));

    ArgumentCaptor<ResultVersionEntity> captor = ArgumentCaptor.forClass(ResultVersionEntity.class);
    verify(mapper).insertReturning(captor.capture());
    ResultVersionEntity inserted = captor.getValue();
    assertThat(inserted.businessKey()).isEqualTo("job:DAILY_PNL:2026-05-04");
    assertThat(inserted.versionNo()).isEqualTo(1);
    assertThat(inserted.status()).isEqualTo("EFFECTIVE");
    assertThat(inserted.effectiveAt()).isNotNull();
    assertThat(inserted.payloadStorage()).isEqualTo("INLINE_JSON");
    assertThat(inserted.payloadJson()).contains("recordCount").contains("42");
    assertThat(inserted.promotionPolicy()).isEqualTo("AUTO_LATEST");
    verify(mapper).lockBusinessKey("t1", "job:DAILY_PNL:2026-05-04");
    verify(mapper).supersedePriorEffective(eq("t1"), eq("job:DAILY_PNL:2026-05-04"), any());
    verify(assetPartitionService).materializeEffectiveJobPartition(eq(instance), any());
  }

  @Test
  @DisplayName("重跑采取新建版本策略时生成新版本并置为生效, 同时作废旧版本")
  void shouldPromoteNewVersionAndSupersede_whenRerunCreatesVersion() {
    JobInstanceEntity instance = success(
        "t1",
        101L,
        "DAILY_PNL",
        LocalDate.of(2026, Month.MAY, 4),
        "{\"resultPolicy\":\"CREATE_NEW_VERSION\"}");
    when(mapper.selectByJobInstanceId("t1", 101L)).thenReturn(null);
    when(mapper.selectMaxVersionNo("t1", "job:DAILY_PNL:2026-05-04")).thenReturn(1);

    writer.writeOnTerminal(instance, Map.of("recordCount", 50));

    ArgumentCaptor<ResultVersionEntity> captor = ArgumentCaptor.forClass(ResultVersionEntity.class);
    verify(mapper).insertReturning(captor.capture());
    assertThat(captor.getValue().versionNo()).isEqualTo(2);
    assertThat(captor.getValue().status()).isEqualTo("EFFECTIVE");
    verify(mapper).supersedePriorEffective(eq("t1"), eq("job:DAILY_PNL:2026-05-04"), any());
  }

  @Test
  @DisplayName("重跑保留双版本时生成待生效版本, 不作废旧版本也不物化")
  void shouldCreatePendingVersion_whenRerunKeepsBoth() {
    JobInstanceEntity instance = success(
        "t1",
        102L,
        "DAILY_PNL",
        LocalDate.of(2026, Month.MAY, 4),
        "{\"resultPolicy\":\"KEEP_BOTH\"}");
    when(mapper.selectByJobInstanceId("t1", 102L)).thenReturn(null);
    when(mapper.selectMaxVersionNo("t1", "job:DAILY_PNL:2026-05-04")).thenReturn(1);

    writer.writeOnTerminal(instance, Map.of());

    ArgumentCaptor<ResultVersionEntity> captor = ArgumentCaptor.forClass(ResultVersionEntity.class);
    verify(mapper).insertReturning(captor.capture());
    assertThat(captor.getValue().status()).isEqualTo("PENDING");
    assertThat(captor.getValue().effectiveAt()).isNull();
    assertThat(captor.getValue().promotionPolicy()).isEqualTo("MANUAL_APPROVAL");
    verify(mapper).lockBusinessKey("t1", "job:DAILY_PNL:2026-05-04");
    verify(mapper, never()).supersedePriorEffective(anyString(), anyString(), any());
    verify(assetPartitionService, never()).materializeEffectiveJobPartition(any(), any());
  }

  @Test
  @DisplayName("重跑要求人工确认生效时生成待生效版本并递增版本号")
  void shouldCreatePendingVersion_whenManualConfirmRequired() {
    JobInstanceEntity instance = success(
        "t1",
        103L,
        "DAILY_PNL",
        LocalDate.of(2026, Month.MAY, 4),
        "{\"resultPolicy\":\"MANUAL_CONFIRM_EFFECTIVE\"}");
    when(mapper.selectByJobInstanceId("t1", 103L)).thenReturn(null);
    when(mapper.selectMaxVersionNo("t1", "job:DAILY_PNL:2026-05-04")).thenReturn(2);

    writer.writeOnTerminal(instance, Map.of("k", "v"));

    ArgumentCaptor<ResultVersionEntity> captor = ArgumentCaptor.forClass(ResultVersionEntity.class);
    verify(mapper).insertReturning(captor.capture());
    assertThat(captor.getValue().status()).isEqualTo("PENDING");
    assertThat(captor.getValue().versionNo()).isEqualTo(3);
  }

  @Test
  @DisplayName("同一作业实例重复上报时只加锁, 不重复写入版本")
  void shouldStayIdempotent_whenTerminalReportRepeats() {
    JobInstanceEntity instance =
        success("t1", 200L, "JOB_A", LocalDate.of(2026, Month.MAY, 4), null);
    when(mapper.selectByJobInstanceId("t1", 200L))
        .thenReturn(ResultVersionEntity.builder().id(99L).versionNo(1).build());

    writer.writeOnTerminal(instance, Map.of("k", "v"));

    verify(mapper).lockBusinessKey("t1", "job:JOB_A:2026-05-04");
    verify(mapper, never()).insertReturning(any());
    verify(mapper, never()).supersedePriorEffective(anyString(), anyString(), any());
    verify(assetPartitionService, never()).materializeEffectiveJobPartition(any(), any());
  }

  @Test
  @DisplayName("实例不是成功终态时不写版本, 也不查询与加锁")
  void shouldSkip_whenInstanceNotSuccessful() {
    JobInstanceEntity instance =
        success("t1", 300L, "JOB_A", LocalDate.of(2026, Month.MAY, 4), null);
    instance.setInstanceStatus("FAILED");

    writer.writeOnTerminal(instance, Map.of("k", "v"));

    verify(mapper, never()).insertReturning(any());
    verify(mapper, never()).lockBusinessKey(anyString(), anyString());
    verify(mapper, never()).selectByJobInstanceId(anyString(), anyLong());
  }

  @Test
  @DisplayName("实例缺少任务编码时不写版本")
  void shouldSkip_whenJobCodeMissing() {
    JobInstanceEntity instance = success("t1", 301L, null, LocalDate.of(2026, Month.MAY, 4), null);

    writer.writeOnTerminal(instance, Map.of());

    verify(mapper, never()).insertReturning(any());
  }

  @Test
  @DisplayName("实例缺少营业日时不写版本")
  void shouldSkip_whenBizDateMissing() {
    JobInstanceEntity instance = success("t1", 302L, "JOB_A", null, null);

    writer.writeOnTerminal(instance, Map.of());

    verify(mapper, never()).insertReturning(any());
  }

  @Test
  @DisplayName("部分失败终态只写待生效版本并转人工审批, 不作废旧版本也不物化")
  void shouldWritePendingOnly_whenInstancePartialFailed() {
    // PARTIAL_FAILED（部分分片失败）不得自动进 EFFECTIVE：否则下游 readiness/asset_partition 会把不
    // 完整结果当完整消费。落 PENDING/MANUAL_APPROVAL、不 supersede 旧 EFFECTIVE、不物化 readiness。
    JobInstanceEntity instance =
        success("t1", 303L, "JOB_A", LocalDate.of(2026, Month.MAY, 4), null);
    instance.setInstanceStatus("PARTIAL_FAILED");
    when(mapper.selectByJobInstanceId("t1", 303L)).thenReturn(null);
    when(mapper.selectMaxVersionNo(anyString(), anyString())).thenReturn(null);

    writer.writeOnTerminal(instance, Map.of("partial", true));

    ArgumentCaptor<ResultVersionEntity> captor = ArgumentCaptor.forClass(ResultVersionEntity.class);
    verify(mapper, times(1)).insertReturning(captor.capture());
    ResultVersionEntity inserted = captor.getValue();
    assertThat(inserted.status()).isEqualTo("PENDING");
    assertThat(inserted.promotionPolicy()).isEqualTo("MANUAL_APPROVAL");
    assertThat(inserted.effectiveAt()).isNull();
    // 不得降级上一版好结果,也不得让下游 readiness 变 READY
    verify(mapper, never()).supersedePriorEffective(anyString(), anyString(), any());
    verify(assetPartitionService, never()).materializeEffectiveJobPartition(any(), any());
  }

  @Test
  @DisplayName("试运行实例写入试运行状态的版本, 不作废旧版本也不物化")
  void shouldWriteDryRunStatus_whenInstanceIsDryRun() {
    JobInstanceEntity instance =
        success("t1", 400L, "DAILY_PNL", LocalDate.of(2026, Month.MAY, 4), null);
    instance.setDryRun(true);
    when(mapper.selectByJobInstanceId("t1", 400L)).thenReturn(null);
    when(mapper.selectMaxVersionNo("t1", "job:DAILY_PNL:2026-05-04")).thenReturn(3);

    writer.writeOnTerminal(instance, Map.of("recordCount", 1));

    ArgumentCaptor<ResultVersionEntity> captor = ArgumentCaptor.forClass(ResultVersionEntity.class);
    verify(mapper).insertReturning(captor.capture());
    assertThat(captor.getValue().status()).isEqualTo("DRY_RUN");
    assertThat(captor.getValue().effectiveAt()).isNull();
    assertThat(captor.getValue().versionNo()).isEqualTo(4);
    verify(mapper).lockBusinessKey("t1", "job:DAILY_PNL:2026-05-04");
    verify(mapper, never()).supersedePriorEffective(anyString(), anyString(), any());
    verify(assetPartitionService, never()).materializeEffectiveJobPartition(any(), any());
  }

  @Test
  @DisplayName("数据质量门禁阻断时强制待生效并转人工审批, 并记录门禁状态")
  void shouldForcePendingAndManualApproval_whenQualityGateBlocked() {
    JobInstanceEntity instance = success(
        "t1",
        500L,
        "DAILY_PNL",
        LocalDate.of(2026, Month.MAY, 4),
        "{\"resultPolicy\":\"CREATE_NEW_VERSION\"}");
    when(mapper.selectByJobInstanceId("t1", 500L)).thenReturn(null);
    when(mapper.selectMaxVersionNo(anyString(), anyString())).thenReturn(null);
    when(dqExecutor.execute(any(), anyString()))
        .thenReturn(io.github.pinpols.batch.orchestrator.application.service.dataquality
            .DataQualityGateOutcome.builder()
            .status(
                io.github.pinpols.batch.orchestrator.application.service.dataquality
                    .DataQualityGateOutcome.GateStatus.BLOCKED)
            .findings(java.util.List.of())
            .build());

    writer.writeOnTerminal(instance, Map.of("recordCount", 1));

    ArgumentCaptor<ResultVersionEntity> captor = ArgumentCaptor.forClass(ResultVersionEntity.class);
    verify(mapper).insertReturning(captor.capture());
    // BLOCKED 强制 PENDING + MANUAL_APPROVAL，不调 supersedePriorEffective
    assertThat(captor.getValue().status()).isEqualTo("PENDING");
    assertThat(captor.getValue().promotionPolicy()).isEqualTo("MANUAL_APPROVAL");
    assertThat(captor.getValue().dqGateStatus()).isEqualTo("BLOCKED");
    verify(mapper, never()).supersedePriorEffective(anyString(), anyString(), any());
    verify(assetPartitionService, never()).materializeEffectiveJobPartition(any(), any());
  }

  @Test
  @DisplayName("产出为空时载荷序列化为空对象")
  void shouldSerializeEmptyObject_whenOutputsEmpty() {
    JobInstanceEntity instance =
        success("t1", 304L, "JOB_A", LocalDate.of(2026, Month.MAY, 4), null);
    when(mapper.selectByJobInstanceId("t1", 304L)).thenReturn(null);
    when(mapper.selectMaxVersionNo(anyString(), anyString())).thenReturn(null);

    writer.writeOnTerminal(instance, null);

    ArgumentCaptor<ResultVersionEntity> captor = ArgumentCaptor.forClass(ResultVersionEntity.class);
    verify(mapper).insertReturning(captor.capture());
    assertThat(captor.getValue().payloadJson()).isEqualTo("{}");
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private JobInstanceEntity success(
      String tenantId, Long id, String jobCode, LocalDate bizDate, String rerunPolicySnapshot) {
    JobInstanceEntity entity = new JobInstanceEntity();
    entity.setTenantId(tenantId);
    entity.setId(id);
    entity.setJobCode(jobCode);
    entity.setBizDate(bizDate);
    entity.setInstanceStatus("SUCCESS");
    entity.setRerunPolicySnapshot(rerunPolicySnapshot);
    return entity;
  }
}
