package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.file.mapper.ConsolePipelineProgressDirtyMapper;
import io.github.pinpols.batch.console.domain.file.mapper.ConsolePipelineProgressDirtyMapper.DirtyCursor;
import io.github.pinpols.batch.console.domain.file.view.PipelineProgressDirtyView;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("流水线进度查询映射: 每个实例只取最新进度,跨租户关联不串号")
class ConsolePipelineProgressDirtyMapperIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;
  private final ConsolePipelineProgressDirtyMapper mapper;

  @Autowired
  ConsolePipelineProgressDirtyMapperIntegrationTest(
      JdbcTemplate jdbcTemplate, ConsolePipelineProgressDirtyMapper mapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.mapper = mapper;
  }

  @Test
  @Transactional
  @DisplayName("相同更新时间超过页大小时，下一页仍能读到剩余实例")
  void shouldReadNextPageWhenManyInstancesShareUpdateTime() {
    String tenantId = unique("tenant-page");
    Instant since = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
    Instant sharedUpdate = since.plusSeconds(1);
    for (int i = 0; i < 3; i++) {
      long pipelineId = insertPipelineInstance(tenantId, null);
      insertProgress(tenantId, pipelineId, "LOAD", sharedUpdate);
    }

    List<PipelineProgressDirtyView> first = mapper.selectUpdatedSince(since, null, 2);
    PipelineProgressDirtyView last = first.getLast();
    DirtyCursor after =
        new DirtyCursor(last.updatedAt(), last.tenantId(), last.pipelineInstanceId());
    List<PipelineProgressDirtyView> second = mapper.selectUpdatedSince(since, after, 2);
    HashSet<Long> seen = new HashSet<>();
    first.forEach(row -> seen.add(row.pipelineInstanceId()));
    second.forEach(row -> seen.add(row.pipelineInstanceId()));

    assertThat(first).hasSize(2);
    assertThat(second).hasSize(1);
    assertThat(seen).hasSize(3);
  }

  @Test
  @DisplayName("进度查询: 每个实例只返回最新一条,他租户关联实例不会污染结果")
  void shouldSelectLatestProgressPerInstance_whenJoiningAcrossTenants() {
    String tenantA = unique("tenant-a");
    String tenantB = unique("tenant-b");
    String nextJobInstanceIdSql = """
        select nextval('batch.job_instance_id_seq')
    """;
    Long relatedJobInstanceId = jdbcTemplate.queryForObject(nextJobInstanceIdSql, Long.class);
    long pipelineA = insertPipelineInstance(tenantA, relatedJobInstanceId);
    long pipelineB = insertPipelineInstance(tenantB, null);
    Instant since = Instant.now().minus(1, ChronoUnit.HOURS);
    Instant older = Instant.now().minus(2, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
    Instant latest = Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);

    insertProgress(tenantA, pipelineA, "LOAD", older);
    insertProgress(tenantA, pipelineA, "GENERATE", latest);
    insertProgress(tenantB, pipelineB, "LOAD", older);
    // 新复合 FK(tenant_id, pipeline_instance_id) 禁止跨租户进度行；改为同租户第二批次进度。
    insertProgress(tenantB, pipelineB, "GENERATE", latest.plusSeconds(30));

    List<PipelineProgressDirtyView> rows = mapper.selectUpdatedSince(since, null, 10);

    assertThat(rows)
        .filteredOn(row -> row.pipelineInstanceId() == pipelineA)
        .singleElement()
        .satisfies(row -> {
          assertThat(row.tenantId()).isEqualTo(tenantA);
          assertThat(row.jobInstanceId()).isEqualTo(relatedJobInstanceId);
          assertThat(row.updatedAt()).isEqualTo(latest);
        });
    assertThat(rows)
        .filteredOn(row -> row.pipelineInstanceId() == pipelineB)
        .singleElement()
        .satisfies(row -> assertThat(row.jobInstanceId()).isNull());
  }

  private long insertPipelineInstance(String tenantId, Long relatedJobInstanceId) {
    String jobCode = unique("job");
    String insertPipelineInstanceSql = """
        insert into batch.pipeline_instance(
          tenant_id, system_pipeline_code, system_pipeline_version, job_code, pipeline_type,
          related_job_instance_id, run_status
        ) values (?, 'IMPORT', 1, ?, 'IMPORT', ?, 'RUNNING')
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertPipelineInstanceSql, Long.class, tenantId, jobCode, relatedJobInstanceId);
  }

  private void insertProgress(
      String tenantId, long pipelineInstanceId, String stage, Instant updatedAt) {
    final String insertPipelineProgressSql = """
    insert into batch.pipeline_progress(
    tenant_id, pipeline_instance_id, stage, processed_count, updated_at
    ) values (?, ?, ?, 1, ?)
    """;
    Timestamp persistedUpdatedAt = Timestamp.from(updatedAt);
    jdbcTemplate.update(
        insertPipelineProgressSql, tenantId, pipelineInstanceId, stage, persistedUpdatedAt);
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}
