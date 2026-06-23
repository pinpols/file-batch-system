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
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

class ResultVersionPromoteServiceTest {

  private ResultVersionMapper mapper;
  private ResultVersionPromoteService service;

  @BeforeEach
  void setUp() {
    mapper = mock(ResultVersionMapper.class);
    BatchDateTimeSupport dateTimeSupport =
        new BatchDateTimeSupport(
            Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
    service = new ResultVersionPromoteService(mapper, dateTimeSupport);
  }

  @Test
  void promotePendingDemotesPriorEffectiveAndPromotes() {
    ResultVersionEntity pending =
        ResultVersionEntity.builder()
            .id(2L)
            .tenantId("t1")
            .businessKey("job:JOB:2026-05-04")
            .versionNo(2)
            .status("PENDING")
            .build();
    ResultVersionEntity promoted =
        ResultVersionEntity.builder()
            .id(2L)
            .tenantId("t1")
            .businessKey("job:JOB:2026-05-04")
            .versionNo(2)
            .status("EFFECTIVE")
            .build();
    when(mapper.selectById("t1", 2L)).thenReturn(pending, promoted);
    when(mapper.promoteToEffective(eq("t1"), eq(2L), any())).thenReturn(1);

    var result = service.promote("t1", 2L);

    assertThat(result.status()).isEqualTo("EFFECTIVE");
    verify(mapper).supersedePriorEffective(eq("t1"), eq("job:JOB:2026-05-04"), any());
    verify(mapper).promoteToEffective(eq("t1"), eq(2L), any());
  }

  @Test
  void promoteRejectsNonPendingState() {
    ResultVersionEntity row =
        ResultVersionEntity.builder()
            .id(1L)
            .tenantId("t1")
            .businessKey("job:JOB:2026-05-04")
            .versionNo(1)
            .status("EFFECTIVE")
            .build();
    when(mapper.selectById("t1", 1L)).thenReturn(row);

    assertThatThrownBy(() -> service.promote("t1", 1L)).isInstanceOf(BizException.class);
    verify(mapper, never()).promoteToEffective(eq("t1"), eq(1L), any());
  }

  @Test
  void promoteRaceLossThrowsOptimisticLockingFailure() {
    ResultVersionEntity pending =
        ResultVersionEntity.builder()
            .id(3L)
            .tenantId("t1")
            .businessKey("job:JOB:2026-05-04")
            .status("PENDING")
            .build();
    when(mapper.selectById("t1", 3L)).thenReturn(pending);
    when(mapper.promoteToEffective(eq("t1"), eq(3L), any())).thenReturn(0);

    assertThatThrownBy(() -> service.promote("t1", 3L))
        .isInstanceOf(OptimisticLockingFailureException.class);
  }

  @Test
  void rejectPendingArchivesIt() {
    ResultVersionEntity pending =
        ResultVersionEntity.builder()
            .id(4L)
            .tenantId("t1")
            .businessKey("job:JOB:2026-05-04")
            .status("PENDING")
            .build();
    ResultVersionEntity archived =
        ResultVersionEntity.builder()
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
  }

  @Test
  void notFoundThrows() {
    when(mapper.selectById("t1", 999L)).thenReturn(null);

    assertThatThrownBy(() -> service.promote("t1", 999L)).isInstanceOf(BizException.class);
  }

  @Test
  void invalidArgumentsThrow() {
    assertThatThrownBy(() -> service.promote(null, 1L)).isInstanceOf(BizException.class);
    assertThatThrownBy(() -> service.rejectPending("t1", null)).isInstanceOf(BizException.class);
  }
}
