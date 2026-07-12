package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.job.entity.JobPartitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.JobPartitionMapper;
import io.github.pinpols.batch.console.domain.job.query.JobPartitionQuery;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 缺口3 mapper IT:FAILED 分区失败原因透出。真实库验证 {@code JobPartitionMapper.selectByQuery} 的 LATERAL JOIN
 * job_task 取该分区最近一次带错误的 task 错误码/消息;非失败分区为 null,并取「最近」而非任意一条。
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class JobPartitionErrorReasonMapperIntegrationTest extends AbstractIntegrationTest {

  @Autowired private JobPartitionMapper jobPartitionMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final AtomicInteger taskSeq = new AtomicInteger(0);

  @Test
  @DisplayName("FAILED 分区透出最近一次失败 task 的错误码/消息;成功分区错误字段为 null")
  void selectByQuery_exposesLatestFailedTaskError() {
    // arrange
    String tenantId = "t-part-err-" + BatchDateTimeSupport.utcEpochMillis();
    long jobInstanceId = insertJobInstance(tenantId);

    long failedPartition = insertPartition(tenantId, jobInstanceId, 0, "FAILED");
    // 更早的失败(error）+ 更晚的失败(最新,应命中):按 finished_at desc 取最新一条。
    insertTask(
        tenantId,
        jobInstanceId,
        failedPartition,
        "FAILED",
        "OLD_ERR",
        "旧错误",
        "2026-07-10T00:00:00Z");
    insertTask(
        tenantId,
        jobInstanceId,
        failedPartition,
        "FAILED",
        "DOWNSTREAM_5XX",
        "下游 502",
        "2026-07-11T00:00:00Z");
    // 无错误的成功 task 不应干扰(error_code is null 被过滤)。
    insertTask(
        tenantId, jobInstanceId, failedPartition, "SUCCESS", null, null, "2026-07-12T00:00:00Z");

    long successPartition = insertPartition(tenantId, jobInstanceId, 1, "SUCCESS");
    insertTask(
        tenantId, jobInstanceId, successPartition, "SUCCESS", null, null, "2026-07-11T00:00:00Z");

    // act
    JobPartitionQuery query = JobPartitionQuery.forJobInstance(tenantId, jobInstanceId);
    List<JobPartitionEntity> rows = jobPartitionMapper.selectByQuery(query);

    // assert
    JobPartitionEntity failed =
        rows.stream().filter(r -> r.getId().equals(failedPartition)).findFirst().orElseThrow();
    assertThat(failed.getErrorCode()).isEqualTo("DOWNSTREAM_5XX");
    assertThat(failed.getErrorMessage()).isEqualTo("下游 502");

    JobPartitionEntity success =
        rows.stream().filter(r -> r.getId().equals(successPartition)).findFirst().orElseThrow();
    assertThat(success.getErrorCode()).isNull();
    assertThat(success.getErrorMessage()).isNull();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private long insertJobInstance(String tenantId) {
    String jobCode = "JOB_" + BatchDateTimeSupport.utcEpochMillis();
    long jobDefinitionId =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO batch.job_definition
              (tenant_id, job_code, job_name, job_type, schedule_type, timezone, created_at, updated_at)
            VALUES (?, ?, ?, 'GENERAL', 'MANUAL', 'Asia/Shanghai', now(), now())
            RETURNING id
            """,
            Long.class,
            tenantId,
            jobCode,
            jobCode + "-name");
    String instanceNo = "INST-" + BatchDateTimeSupport.utcEpochMillis();
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO batch.job_instance
          (tenant_id, job_definition_id, job_code, instance_no, biz_date,
           trigger_type, instance_status, priority, dedup_key, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, 'MANUAL', 'PARTIAL_FAILED', 5, ?, now(), now())
        RETURNING id
        """,
        Long.class,
        tenantId,
        jobDefinitionId,
        jobCode,
        instanceNo,
        Date.valueOf(LocalDate.now()),
        tenantId + ":" + instanceNo);
  }

  private long insertPartition(
      String tenantId, long jobInstanceId, int partitionNo, String status) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO batch.job_partition
          (tenant_id, job_instance_id, partition_no, partition_status, created_at, updated_at)
        VALUES (?, ?, ?, ?, now(), now())
        RETURNING id
        """,
        Long.class,
        tenantId,
        jobInstanceId,
        partitionNo,
        status);
  }

  private void insertTask(
      String tenantId,
      long jobInstanceId,
      long partitionId,
      String status,
      String errorCode,
      String errorMessage,
      String finishedAtIso) {
    jdbcTemplate.update(
        """
        INSERT INTO batch.job_task
          (tenant_id, job_instance_id, job_partition_id, task_type, task_seq, task_status,
           error_code, error_message, finished_at, created_at, updated_at)
        VALUES (?, ?, ?, 'EXECUTION', ?, ?, ?, ?, ?::timestamptz, now(), now())
        """,
        tenantId,
        jobInstanceId,
        partitionId,
        taskSeq.incrementAndGet(),
        status,
        errorCode,
        errorMessage,
        finishedAtIso);
  }
}
