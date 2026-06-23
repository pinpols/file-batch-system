package io.github.pinpols.batch.orchestrator.application.service.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResultVersionQueryServiceTest {

  private ResultVersionMapper mapper;
  private ResultVersionQueryService service;

  @BeforeEach
  void setUp() {
    mapper = mock(ResultVersionMapper.class);
    service = new ResultVersionQueryService(mapper);
  }

  @Test
  void findEffectiveReturnsRowWhenPresent() {
    ResultVersionEntity row =
        ResultVersionEntity.builder()
            .id(1L)
            .tenantId("t1")
            .businessKey("job:JOB_A:2026-05-04")
            .versionNo(2)
            .status("EFFECTIVE")
            .build();
    when(mapper.selectEffective("t1", "job:JOB_A:2026-05-04")).thenReturn(row);

    var found = service.findEffective("t1", "job:JOB_A:2026-05-04");

    assertThat(found).isPresent();
    assertThat(found.get().versionNo()).isEqualTo(2);
  }

  @Test
  void findEffectiveReturnsEmptyOnNullInputs() {
    assertThat(service.findEffective(null, "job:JOB:2026-05-04")).isEmpty();
    assertThat(service.findEffective("t1", null)).isEmpty();
    assertThat(service.findEffective("", "")).isEmpty();
    verify(mapper, never()).selectEffective(eq("t1"), eq("job:JOB:2026-05-04"));
  }

  @Test
  void findEffectiveByJobDerivesBusinessKey() {
    ResultVersionEntity row =
        ResultVersionEntity.builder().id(1L).businessKey("job:JOB_A:2026-05-04").build();
    when(mapper.selectEffective("t1", "job:JOB_A:2026-05-04")).thenReturn(row);

    var found = service.findEffectiveByJob("t1", "JOB_A", LocalDate.of(2026, 5, 4));

    assertThat(found).isPresent();
    verify(mapper).selectEffective("t1", "job:JOB_A:2026-05-04");
  }

  @Test
  void findEffectiveByJobReturnsEmptyOnNullBizDate() {
    assertThat(service.findEffectiveByJob("t1", "JOB_A", null)).isEmpty();
    verify(mapper, never()).selectEffective(eq("t1"), eq("any"));
  }

  @Test
  void listVersionsRespectsLimitAndDelegatesToMapper() {
    ResultVersionEntity v3 =
        ResultVersionEntity.builder().id(3L).versionNo(3).status("EFFECTIVE").build();
    ResultVersionEntity v2 =
        ResultVersionEntity.builder().id(2L).versionNo(2).status("SUPERSEDED").build();
    when(mapper.listVersionsByBusinessKey("t1", "job:JOB_A:2026-05-04", 50))
        .thenReturn(List.of(v3, v2));

    var versions = service.listVersions("t1", "job:JOB_A:2026-05-04", 50);

    assertThat(versions).hasSize(2);
    assertThat(versions.get(0).versionNo()).isEqualTo(3);
    assertThat(versions.get(1).versionNo()).isEqualTo(2);
  }

  @Test
  void listVersionsReturnsEmptyOnInvalidLimit() {
    assertThat(service.listVersions("t1", "job:JOB_A:2026-05-04", 0)).isEmpty();
    assertThat(service.listVersions("t1", "job:JOB_A:2026-05-04", -1)).isEmpty();
  }
}
