package com.example.batch.orchestrator.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName(
    "分区后 job_instance 幂等:同 (tenant,dedup_key,attempt,biz_date) 仍抛 DuplicateKey;NULL biz_date 由 SQL"
        + " 兜底")
class JobInstanceDedupPartitionIntegrationTest extends AbstractIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  /** 测试自洽的最小 job_definition 种子 id,由 @BeforeEach 插入后赋值。 */
  private long jobDefinitionId;

  @BeforeEach
  void insertJobDefinitionSeed() {
    // 插入最小 job_definition 种子行,满足 job_instance.job_definition_id FK。
    // 每次测试前 insert,利用 Testcontainers 事务回滚(或唯一 suffix)保持隔离。
    String suffix = String.valueOf(System.nanoTime());
    jdbcTemplate.update(
        """
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
            priority, queue_code, worker_group, trigger_mode, dag_enabled, shard_strategy,
            retry_policy, retry_max_count, timeout_seconds, enabled, version
        ) values ('ta', ?, 'dedup-it-job', 'IMPORT', 'IT', 'MANUAL', 'UTC',
            5, 'q-it', 'wg-it', 'API', false, 'NONE', 'NONE', 0, 0, true, 1)
        """,
        "JOB-DEDUP-" + suffix);

    jobDefinitionId =
        jdbcTemplate.queryForObject(
            "select id from batch.job_definition where tenant_id='ta' and job_code=?",
            Long.class,
            "JOB-DEDUP-" + suffix);
  }

  private int rawInsert(String dedupKey, LocalDate bizDate) {
    return jdbcTemplate.update(
        "insert into batch.job_instance (tenant_id, job_definition_id, job_code, instance_no,"
            + " biz_date, trigger_type, instance_status, dedup_key, run_attempt)"
            + " values ('ta', ?, 'JOB-A', 'no-' || clock_timestamp()::text,"
            + " coalesce(?, CURRENT_DATE), 'MANUAL', 'CREATED', ?, 1)",
        jobDefinitionId,
        bizDate,
        dedupKey);
  }

  @Test
  void shouldThrowDuplicateKey_whenSameDedupKeySameBizDate() {
    String dedup = "dedup-it-" + System.nanoTime();
    rawInsert(dedup, LocalDate.of(2026, 6, 10));
    assertThatThrownBy(() -> rawInsert(dedup, LocalDate.of(2026, 6, 10)))
        .as("同 (tenant,dedup_key,attempt,biz_date) 第二次插入")
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void shouldRouteToCurrentMonthPartition_whenBizDateNull() {
    String dedup = "dedup-null-" + System.nanoTime();
    rawInsert(dedup, null);
    String partition =
        jdbcTemplate.queryForObject(
            "SELECT tableoid::regclass::text FROM batch.job_instance WHERE dedup_key=?",
            String.class,
            dedup);
    assertThat(partition)
        .as("NULL biz_date 应 COALESCE 落当月分区,而非 default")
        .contains("job_instance_p_2026");
  }
}
