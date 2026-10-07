package io.github.pinpols.batch.orchestrator.application.service.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.service.asset.AssetPartitionService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Month;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("结果版本生效服务: 生效,驳回,并发冲突与参数校验的处理口径")
class ResultVersionPromoteServiceTest {

  private ResultVersionMapper mapper;
  private JobInstanceMapper jobInstanceMapper;
  private AssetPartitionService assetPartitionService;
  private ResultVersionPromoteService service;

  @BeforeEach
  void setUp() {
    mapper = mock(ResultVersionMapper.class);
    jobInstanceMapper = mock(JobInstanceMapper.class);
    assetPartitionService = mock(AssetPartitionService.class);
    BatchDateTimeSupport dateTimeSupport = new BatchDateTimeSupport(
        Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
    service = new ResultVersionPromoteService(
        mapper, jobInstanceMapper, dateTimeSupport, assetPartitionService);
  }

  @Test
  @DisplayName("待生效版本成功抢占时先作废旧生效版本,再置为新生效并物化分区")
  void shouldPromoteAndSupersedePrior_whenPendingVersionConfirmed() {
    ResultVersionEntity pending = ResultVersionEntity.builder()
        .id(2L)
        .tenantId("t1")
        .businessKey("job:JOB:2026-05-04")
        .versionNo(2)
        .status("PENDING")
        .build();
    ResultVersionEntity promoted = ResultVersionEntity.builder()
        .id(2L)
        .tenantId("t1")
        .businessKey("job:JOB:2026-05-04")
        .versionNo(2)
        .jobInstanceId(200L)
        .status("EFFECTIVE")
        .build();
    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setTenantId("t1");
    instance.setId(200L);
    instance.setJobCode("JOB");
    instance.setBizDate(LocalDate.of(2026, Month.MAY, 4));
    when(mapper.selectById("t1", 2L)).thenReturn(pending, promoted);
    when(mapper.promoteToEffective(eq("t1"), eq(2L), any())).thenReturn(1);
    when(jobInstanceMapper.selectById("t1", 200L)).thenReturn(instance);

    var result = service.promote("t1", 2L);

    assertThat(result.status()).isEqualTo("EFFECTIVE");
    verify(mapper).supersedePriorEffective(eq("t1"), eq("job:JOB:2026-05-04"), any());
    verify(mapper).promoteToEffective(eq("t1"), eq(2L), any());
    verify(assetPartitionService).materializeEffectiveJobPartition(instance, promoted);
  }

  @Test
  @DisplayName("版本不处于待生效状态时抛出业务异常,且不写生效记录")
  void shouldRejectPromotion_whenStateNotPending() {
    ResultVersionEntity row = ResultVersionEntity.builder()
        .id(1L)
        .tenantId("t1")
        .businessKey("job:JOB:2026-05-04")
        .versionNo(1)
        .status("EFFECTIVE")
        .build();
    when(mapper.selectById("t1", 1L)).thenReturn(row);

    assertThatThrownBy(() -> service.promote("t1", 1L)).isInstanceOf(BizException.class);
    verify(mapper, never()).promoteToEffective(eq("t1"), eq(1L), any());
    verify(assetPartitionService, never()).materializeEffectiveJobPartition(any(), any());
  }

  @Test
  @DisplayName("并发导致生效更新未命中任何行时抛出乐观锁异常,且不物化分区")
  void shouldFailOnRaceLoss_whenUpdateAffectsNoRow() {
    ResultVersionEntity pending = ResultVersionEntity.builder()
        .id(3L)
        .tenantId("t1")
        .businessKey("job:JOB:2026-05-04")
        .status("PENDING")
        .build();
    when(mapper.selectById("t1", 3L)).thenReturn(pending);
    when(mapper.promoteToEffective(eq("t1"), eq(3L), any())).thenReturn(0);

    assertThatThrownBy(() -> service.promote("t1", 3L))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(ResultCode.STATE_CONFLICT);
    verify(assetPartitionService, never()).materializeEffectiveJobPartition(any(), any());
  }

  @Test
  @DisplayName("驳回待生效版本时将其置为归档,且不影响已有生效版本")
  void shouldArchive_whenPendingVersionRejected() {
    ResultVersionEntity pending = ResultVersionEntity.builder()
        .id(4L)
        .tenantId("t1")
        .businessKey("job:JOB:2026-05-04")
        .status("PENDING")
        .build();
    ResultVersionEntity archived = ResultVersionEntity.builder()
        .id(4L)
        .tenantId("t1")
        .businessKey("job:JOB:2026-05-04")
        .status("ARCHIVED")
        .build();
    when(mapper.selectById("t1", 4L)).thenReturn(pending, archived);
    when(mapper.rejectPending(eq("t1"), eq(4L), any())).thenReturn(1);

    var result = service.rejectPending("t1", 4L);

    assertThat(result.status()).isEqualTo("ARCHIVED");
    verify(mapper, never()).supersedePriorEffective(eq("t1"), eq("job:JOB:2026-05-04"), any());
    verify(assetPartitionService, never()).materializeEffectiveJobPartition(any(), any());
  }

  @Test
  @DisplayName("并发导致驳回更新未命中任何行时返回状态冲突")
  void shouldFailOnRejectRaceLoss_whenUpdateAffectsNoRow() {
    ResultVersionEntity pending = ResultVersionEntity.builder()
        .id(5L)
        .tenantId("t1")
        .businessKey("job:JOB:2026-05-04")
        .status("PENDING")
        .build();
    when(mapper.selectById("t1", 5L)).thenReturn(pending);
    when(mapper.rejectPending(eq("t1"), eq(5L), any())).thenReturn(0);

    assertThatThrownBy(() -> service.rejectPending("t1", 5L))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(ResultCode.STATE_CONFLICT);
  }

  @Test
  @DisplayName("版本不存在时抛出业务异常")
  void shouldThrow_whenVersionMissing() {
    when(mapper.selectById("t1", 999L)).thenReturn(null);

    assertThatThrownBy(() -> service.promote("t1", 999L)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("租户或版本标识为空时抛出业务异常")
  void shouldThrow_whenArgumentsInvalid() {
    assertThatThrownBy(() -> service.promote(null, 1L)).isInstanceOf(BizException.class);
    assertThatThrownBy(() -> service.rejectPending("t1", null)).isInstanceOf(BizException.class);
  }
}
