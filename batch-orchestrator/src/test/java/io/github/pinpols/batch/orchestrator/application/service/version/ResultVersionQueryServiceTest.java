package io.github.pinpols.batch.orchestrator.application.service.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("结果版本查询服务: 生效版本判定与版本列表查询口径")
class ResultVersionQueryServiceTest {

  private ResultVersionMapper mapper;
  private ResultVersionQueryService service;

  @BeforeEach
  void setUp() {
    mapper = mock(ResultVersionMapper.class);
    service = new ResultVersionQueryService(mapper);
  }

  @Test
  @DisplayName("按业务键能查到生效版本时返回该版本并带出版本号")
  void shouldReturnEffectiveVersion_whenRowExists() {
    ResultVersionEntity row = ResultVersionEntity.builder()
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
  @DisplayName("租户或业务键为空时返回空,且不访问存储")
  void shouldReturnEmpty_whenInputsBlank() {
    assertThat(service.findEffective(null, "job:JOB:2026-05-04")).isEmpty();
    assertThat(service.findEffective("t1", null)).isEmpty();
    assertThat(service.findEffective("", "")).isEmpty();
    verify(mapper, never()).selectEffective("t1", "job:JOB:2026-05-04");
  }

  @Test
  @DisplayName("按任务与营业日查询时推导出业务键并委托存储查询")
  void shouldDeriveBusinessKey_whenQueryingByJobAndBizDate() {
    ResultVersionEntity row = ResultVersionEntity.builder()
        .id(1L)
        .businessKey("job:JOB_A:2026-05-04")
        .status("EFFECTIVE")
        .build();
    when(mapper.listVersionsByBusinessKey("t1", "job:JOB_A:2026-05-04", 1))
        .thenReturn(List.of(row));

    var found = service.findEffectiveByJob("t1", "JOB_A", LocalDate.of(2026, Month.MAY, 4));

    assertThat(found).isPresent();
    verify(mapper).listVersionsByBusinessKey("t1", "job:JOB_A:2026-05-04", 1);
  }

  @Test
  @DisplayName("最近一次产出仍处于待生效状态时返回空")
  void shouldReturnEmpty_whenLatestAttemptStillPending() {
    ResultVersionEntity row = ResultVersionEntity.builder()
        .id(2L)
        .businessKey("job:JOB_A:2026-05-04")
        .status("PENDING")
        .build();
    when(mapper.listVersionsByBusinessKey("t1", "job:JOB_A:2026-05-04", 1))
        .thenReturn(List.of(row));

    var found = service.findEffectiveByJob("t1", "JOB_A", LocalDate.of(2026, Month.MAY, 4));

    assertThat(found).isEmpty();
  }

  @Test
  @DisplayName("营业日为空时返回空,且不做版本查询")
  void shouldReturnEmpty_whenBizDateMissing() {
    assertThat(service.findEffectiveByJob("t1", "JOB_A", null)).isEmpty();
    verify(mapper, never()).selectEffective("t1", "any");
  }

  @Test
  @DisplayName("按任务查询最新版本时返回存储给出的首条版本")
  void shouldReturnLatestVersion_whenQueryingByJob() {
    ResultVersionEntity row = ResultVersionEntity.builder()
        .id(2L)
        .businessKey("job:JOB_A:2026-05-04")
        .versionNo(4)
        .status("PENDING")
        .build();
    when(mapper.listVersionsByBusinessKey("t1", "job:JOB_A:2026-05-04", 1))
        .thenReturn(List.of(row));

    var found = service.findLatestByJob("t1", "JOB_A", LocalDate.of(2026, Month.MAY, 4));

    assertThat(found).contains(row);
    verify(mapper).listVersionsByBusinessKey("t1", "job:JOB_A:2026-05-04", 1);
  }

  @Test
  @DisplayName("列出版本时按给定条数上限查询并按版本号倒序返回")
  void shouldRespectLimit_whenListingVersions() {
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
  @DisplayName("条数上限非正时返回空列表")
  void shouldReturnEmpty_whenLimitNotPositive() {
    assertThat(service.listVersions("t1", "job:JOB_A:2026-05-04", 0)).isEmpty();
    assertThat(service.listVersions("t1", "job:JOB_A:2026-05-04", -1)).isEmpty();
  }
}
