package com.example.batch.orchestrator.application.service.version;

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

import com.example.batch.common.config.BatchTimezoneProperties;
import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.ResultVersionEntity;
import com.example.batch.orchestrator.mapper.ResultVersionMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ResultVersionWriterTest {

  private ResultVersionMapper mapper;
  private ResultVersionWriter writer;

  @BeforeEach
  void setUp() {
    mapper = mock(ResultVersionMapper.class);
    BatchDateTimeSupport dateTimeSupport =
        new BatchDateTimeSupport(
            Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
    writer = new ResultVersionWriter(mapper, dateTimeSupport);
  }

  @Test
  void firstSuccessRunWritesV1Effective() {
    JobInstanceEntity instance = success("t1", 100L, "DAILY_PNL", LocalDate.of(2026, 5, 4), null);
    when(mapper.selectByJobInstanceId("t1", 100L)).thenReturn(null);
    when(mapper.selectMaxVersionNo("t1", "job:DAILY_PNL:2026-05-04")).thenReturn(null);

    writer.writeOnTerminal(instance, Map.of("recordCount", 42));

    ArgumentCaptor<ResultVersionEntity> captor = ArgumentCaptor.forClass(ResultVersionEntity.class);
    verify(mapper).insert(captor.capture());
    ResultVersionEntity inserted = captor.getValue();
    assertThat(inserted.businessKey()).isEqualTo("job:DAILY_PNL:2026-05-04");
    assertThat(inserted.versionNo()).isEqualTo(1);
    assertThat(inserted.status()).isEqualTo("EFFECTIVE");
    assertThat(inserted.effectiveAt()).isNotNull();
    assertThat(inserted.payloadStorage()).isEqualTo("INLINE_JSON");
    assertThat(inserted.payloadJson()).contains("recordCount").contains("42");
    assertThat(inserted.promotionPolicy()).isEqualTo("AUTO_LATEST");
    verify(mapper).supersedePriorEffective(eq("t1"), eq("job:DAILY_PNL:2026-05-04"), any());
  }

  @Test
  void rerunWithCreateNewVersionPromotesV2EffectiveAndSupersedesV1() {
    JobInstanceEntity instance =
        success(
            "t1",
            101L,
            "DAILY_PNL",
            LocalDate.of(2026, 5, 4),
            "{\"resultPolicy\":\"CREATE_NEW_VERSION\"}");
    when(mapper.selectByJobInstanceId("t1", 101L)).thenReturn(null);
    when(mapper.selectMaxVersionNo("t1", "job:DAILY_PNL:2026-05-04")).thenReturn(1);

    writer.writeOnTerminal(instance, Map.of("recordCount", 50));

    ArgumentCaptor<ResultVersionEntity> captor = ArgumentCaptor.forClass(ResultVersionEntity.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().versionNo()).isEqualTo(2);
    assertThat(captor.getValue().status()).isEqualTo("EFFECTIVE");
    verify(mapper).supersedePriorEffective(eq("t1"), eq("job:DAILY_PNL:2026-05-04"), any());
  }

  @Test
  void rerunWithKeepBothCreatesPendingVersion() {
    JobInstanceEntity instance =
        success(
            "t1", 102L, "DAILY_PNL", LocalDate.of(2026, 5, 4), "{\"resultPolicy\":\"KEEP_BOTH\"}");
    when(mapper.selectByJobInstanceId("t1", 102L)).thenReturn(null);
    when(mapper.selectMaxVersionNo("t1", "job:DAILY_PNL:2026-05-04")).thenReturn(1);

    writer.writeOnTerminal(instance, Map.of());

    ArgumentCaptor<ResultVersionEntity> captor = ArgumentCaptor.forClass(ResultVersionEntity.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().status()).isEqualTo("PENDING");
    assertThat(captor.getValue().effectiveAt()).isNull();
    assertThat(captor.getValue().promotionPolicy()).isEqualTo("MANUAL_APPROVAL");
    verify(mapper, never()).supersedePriorEffective(anyString(), anyString(), any());
  }

  @Test
  void rerunWithManualConfirmEffectiveCreatesPendingVersion() {
    JobInstanceEntity instance =
        success(
            "t1",
            103L,
            "DAILY_PNL",
            LocalDate.of(2026, 5, 4),
            "{\"resultPolicy\":\"MANUAL_CONFIRM_EFFECTIVE\"}");
    when(mapper.selectByJobInstanceId("t1", 103L)).thenReturn(null);
    when(mapper.selectMaxVersionNo("t1", "job:DAILY_PNL:2026-05-04")).thenReturn(2);

    writer.writeOnTerminal(instance, Map.of("k", "v"));

    ArgumentCaptor<ResultVersionEntity> captor = ArgumentCaptor.forClass(ResultVersionEntity.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().status()).isEqualTo("PENDING");
    assertThat(captor.getValue().versionNo()).isEqualTo(3);
  }

  @Test
  void duplicateReportIsIdempotent() {
    JobInstanceEntity instance = success("t1", 200L, "JOB_A", LocalDate.of(2026, 5, 4), null);
    when(mapper.selectByJobInstanceId("t1", 200L))
        .thenReturn(ResultVersionEntity.builder().id(99L).versionNo(1).build());

    writer.writeOnTerminal(instance, Map.of("k", "v"));

    verify(mapper, never()).insert(any());
    verify(mapper, never()).supersedePriorEffective(anyString(), anyString(), any());
  }

  @Test
  void nonSuccessTerminalIsSkipped() {
    JobInstanceEntity instance = success("t1", 300L, "JOB_A", LocalDate.of(2026, 5, 4), null);
    instance.setInstanceStatus("FAILED");

    writer.writeOnTerminal(instance, Map.of("k", "v"));

    verify(mapper, never()).insert(any());
    verify(mapper, never()).selectByJobInstanceId(anyString(), anyLong());
  }

  @Test
  void missingJobCodeIsSkipped() {
    JobInstanceEntity instance = success("t1", 301L, null, LocalDate.of(2026, 5, 4), null);

    writer.writeOnTerminal(instance, Map.of());

    verify(mapper, never()).insert(any());
  }

  @Test
  void missingBizDateIsSkipped() {
    JobInstanceEntity instance = success("t1", 302L, "JOB_A", null, null);

    writer.writeOnTerminal(instance, Map.of());

    verify(mapper, never()).insert(any());
  }

  @Test
  void partialFailedTerminalAlsoWrites() {
    JobInstanceEntity instance = success("t1", 303L, "JOB_A", LocalDate.of(2026, 5, 4), null);
    instance.setInstanceStatus("PARTIAL_FAILED");
    when(mapper.selectByJobInstanceId("t1", 303L)).thenReturn(null);
    when(mapper.selectMaxVersionNo(anyString(), anyString())).thenReturn(null);

    writer.writeOnTerminal(instance, Map.of("partial", true));

    verify(mapper, times(1)).insert(any());
  }

  @Test
  void dryRunInstanceWritesDryRunStatusWithoutSupersede() {
    JobInstanceEntity instance = success("t1", 400L, "DAILY_PNL", LocalDate.of(2026, 5, 4), null);
    instance.setDryRun(true);
    when(mapper.selectByJobInstanceId("t1", 400L)).thenReturn(null);
    when(mapper.selectMaxVersionNo("t1", "job:DAILY_PNL:2026-05-04")).thenReturn(3);

    writer.writeOnTerminal(instance, Map.of("recordCount", 1));

    ArgumentCaptor<ResultVersionEntity> captor = ArgumentCaptor.forClass(ResultVersionEntity.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().status()).isEqualTo("DRY_RUN");
    assertThat(captor.getValue().effectiveAt()).isNull();
    assertThat(captor.getValue().versionNo()).isEqualTo(4);
    verify(mapper, never()).supersedePriorEffective(anyString(), anyString(), any());
  }

  @Test
  void emptyOutputsSerializeAsEmptyObject() {
    JobInstanceEntity instance = success("t1", 304L, "JOB_A", LocalDate.of(2026, 5, 4), null);
    when(mapper.selectByJobInstanceId("t1", 304L)).thenReturn(null);
    when(mapper.selectMaxVersionNo(anyString(), anyString())).thenReturn(null);

    writer.writeOnTerminal(instance, null);

    ArgumentCaptor<ResultVersionEntity> captor = ArgumentCaptor.forClass(ResultVersionEntity.class);
    verify(mapper).insert(captor.capture());
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
