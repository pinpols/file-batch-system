package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.file.mapper.ConsolePipelineProgressDirtyMapper;
import io.github.pinpols.batch.console.domain.file.view.PipelineProgressDirtyView;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
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
  void selectsLatestProgressWithoutCrossTenantJoinLeakage() {
    String tenantA = unique("tenant-a");
    String tenantB = unique("tenant-b");
    Long relatedJobInstanceId =
        jdbcTemplate.queryForObject("select nextval('batch.job_instance_id_seq')", Long.class);
    long pipelineA = insertPipelineInstance(tenantA, relatedJobInstanceId);
    long pipelineB = insertPipelineInstance(tenantB, null);
    Instant since = Instant.now().minus(1, ChronoUnit.HOURS);
    Instant older = Instant.now().minus(2, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
    Instant latest = Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);

    insertProgress(tenantA, pipelineA, "LOAD", older);
    insertProgress(tenantA, pipelineA, "GENERATE", latest);
    insertProgress(tenantB, pipelineB, "LOAD", older);
    insertProgress(tenantB, pipelineA, "LOAD", latest.plusSeconds(30));

    List<PipelineProgressDirtyView> rows = mapper.selectUpdatedSince(since, 10);

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
    Long definitionId = jdbcTemplate.queryForObject("""
            insert into batch.pipeline_definition(
              tenant_id, job_code, pipeline_name, pipeline_type
            ) values (?, ?, 'Dirty Progress Test', 'IMPORT')
            returning id
            """, Long.class, tenantId, jobCode);
    return jdbcTemplate.queryForObject(
        """
        insert into batch.pipeline_instance(
          tenant_id, pipeline_definition_id, job_code, pipeline_type,
          related_job_instance_id, run_status
        ) values (?, ?, ?, 'IMPORT', ?, 'RUNNING')
        returning id
        """, Long.class, tenantId, definitionId, jobCode, relatedJobInstanceId);
  }

  private void insertProgress(
      String tenantId, long pipelineInstanceId, String stage, Instant updatedAt) {
    jdbcTemplate.update("""
        insert into batch.pipeline_progress(
          tenant_id, pipeline_instance_id, stage, processed_count, updated_at
        ) values (?, ?, ?, 1, ?)
        """, tenantId, pipelineInstanceId, stage, Timestamp.from(updatedAt));
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}
