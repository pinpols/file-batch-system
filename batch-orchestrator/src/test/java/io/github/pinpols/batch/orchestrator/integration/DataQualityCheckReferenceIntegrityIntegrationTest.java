package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.domain.entity.DataQualityCheckEntity;
import io.github.pinpols.batch.orchestrator.mapper.DataQualityCheckMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = "batch.result-version.retention.enabled=false")
@DisplayName("DQ 检查的 JobInstance 历史引用与归档删除并发保护")
class DataQualityCheckReferenceIntegrityIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;
  private final DataQualityCheckMapper checkMapper;
  private final PlatformTransactionManager transactionManager;

  @Autowired
  DataQualityCheckReferenceIntegrityIntegrationTest(
      JdbcTemplate jdbcTemplate,
      DataQualityCheckMapper checkMapper,
      PlatformTransactionManager transactionManager) {
    this.jdbcTemplate = jdbcTemplate;
    this.checkMapper = checkMapper;
    this.transactionManager = transactionManager;
  }

  @Test
  @DisplayName("只允许把 DQ 检查链接到当前租户唯一存在的 JobInstance")
  void shouldRejectMissingOrCrossTenantJobInstance() {
    JobFixture fixture = insertJobInstance(unique("owner"));

    assertThatThrownBy(() -> checkMapper.insert(check(unique("other"), fixture.jobInstanceId())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing or different-tenant JobInstance");
    assertThatThrownBy(() -> checkMapper.insert(
            check(fixture.tenantId(), fixture.jobInstanceId() + 1_000_000_000L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing or different-tenant JobInstance");

    assertThat(checkCount(fixture.tenantId(), fixture.jobInstanceId())).isZero();
  }

  @Test
  @DisplayName("数据库触发器拒绝直接 SQL 写入的异租户或不存在实例")
  void shouldRejectDirectSqlMissingOrCrossTenantJobInstance() {
    JobFixture owner = insertJobInstance(unique("direct-owner"));
    JobFixture otherTenant = insertJobInstance(unique("direct-other"));

    assertThatThrownBy(() -> insertDirectCheck(otherTenant.tenantId(), owner.jobInstanceId()))
        .satisfies(DataQualityCheckReferenceIntegrityIntegrationTest::assertDqConstraintViolation);
    assertThatThrownBy(() -> insertDirectCheck(owner.tenantId(), owner.jobInstanceId() + 1_000_000_000L))
        .satisfies(DataQualityCheckReferenceIntegrityIntegrationTest::assertDqConstraintViolation);

    assertThat(checkCount(owner.tenantId(), owner.jobInstanceId())).isZero();
  }

  @Test
  @DisplayName("数据库触发器在 DQ 历史引用存在时拒绝改写父实例身份")
  void shouldRejectRekeyingReferencedJobInstance() {
    JobFixture fixture = insertJobInstance(unique("rekey"));
    checkMapper.insert(check(fixture.tenantId(), fixture.jobInstanceId()));

    assertThatThrownBy(() -> jdbcTemplate.update(
            "update batch.job_instance set id = id + 1_000_000_000 where tenant_id = ? and id = ?",
            fixture.tenantId(),
            fixture.jobInstanceId()))
        .satisfies(DataQualityCheckReferenceIntegrityIntegrationTest::assertDqConstraintViolation);
    assertThatThrownBy(() -> jdbcTemplate.update(
            "update batch.job_instance set biz_date = biz_date + 1 where tenant_id = ? and id = ?",
            fixture.tenantId(),
            fixture.jobInstanceId()))
        .satisfies(DataQualityCheckReferenceIntegrityIntegrationTest::assertDqConstraintViolation);

    assertThat(jobInstanceCount(fixture.tenantId(), fixture.jobInstanceId())).isEqualTo(1);
    assertThat(checkCount(fixture.tenantId(), fixture.jobInstanceId())).isEqualTo(1);
  }

  @Test
  @DisplayName("DQ 直写先锁定父身份时，父业务日改写等待并在提交后被拒绝")
  void shouldRejectParentRekeyWhenDirectSqlAttachmentWinsConcurrentUpdate() throws Exception {
    JobFixture fixture = insertJobInstance(unique("direct-rekey-race"));
    LocalDate originalBizDate = jdbcTemplate.queryForObject(
        "select biz_date from batch.job_instance where tenant_id = ? and id = ?",
        LocalDate.class,
        fixture.tenantId(),
        fixture.jobInstanceId());
    CountDownLatch checkInserted = new CountDownLatch(1);
    CountDownLatch allowCheckCommit = new CountDownLatch(1);
    CountDownLatch rekeyStarted = new CountDownLatch(1);
    AtomicInteger rekeyPid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);

    Future<?> insert = executor.submit(() -> new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          insertDirectCheck(fixture.tenantId(), fixture.jobInstanceId());
          checkInserted.countDown();
          awaitLatch(allowCheckCommit);
        }));
    Future<?> rekey = null;
    try {
      assertThat(checkInserted.await(5, TimeUnit.SECONDS)).isTrue();
      rekey = executor.submit(() -> new TransactionTemplate(transactionManager)
          .executeWithoutResult(status -> {
            rekeyPid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
            rekeyStarted.countDown();
            jdbcTemplate.update(
                "update batch.job_instance set biz_date = biz_date + 1 where tenant_id = ? and id = ?",
                fixture.tenantId(),
                fixture.jobInstanceId());
          }));
      assertThat(rekeyStarted.await(5, TimeUnit.SECONDS)).isTrue();
      awaitBlocked(rekeyPid.get());

      allowCheckCommit.countDown();
      insert.get(10, TimeUnit.SECONDS);
      Future<?> rekeyFuture = rekey;
      assertThatThrownBy(() -> rekeyFuture.get(10, TimeUnit.SECONDS))
          .satisfies(DataQualityCheckReferenceIntegrityIntegrationTest::assertDqConstraintViolation);

      assertThat(jobInstanceCount(fixture.tenantId(), fixture.jobInstanceId())).isEqualTo(1);
      assertThat(jdbcTemplate.queryForObject(
              "select biz_date from batch.job_instance where tenant_id = ? and id = ?",
              LocalDate.class,
              fixture.tenantId(),
              fixture.jobInstanceId()))
          .isEqualTo(originalBizDate);
      assertThat(checkCount(fixture.tenantId(), fixture.jobInstanceId())).isEqualTo(1);
    } finally {
      allowCheckCommit.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("DQ 检查先锁定父实例时允许后续归档删除并保留历史 ID")
  void shouldKeepHistoricalReferenceWhenDeleteFollowsCheckInsert() throws Exception {
    JobFixture fixture = insertJobInstance(unique("delete-after-write"));
    CountDownLatch checkInserted = new CountDownLatch(1);
    CountDownLatch allowCheckCommit = new CountDownLatch(1);
    CountDownLatch deleteStarted = new CountDownLatch(1);
    AtomicInteger deletePid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);

    Future<?> insert = executor.submit(() -> new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          checkMapper.insert(check(fixture.tenantId(), fixture.jobInstanceId()));
          checkInserted.countDown();
          awaitLatch(allowCheckCommit);
        }));
    Future<?> delete = null;
    try {
      assertThat(checkInserted.await(5, TimeUnit.SECONDS)).isTrue();
      delete = executor.submit(() -> new TransactionTemplate(transactionManager)
          .executeWithoutResult(status -> {
            deletePid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
            deleteStarted.countDown();
            jdbcTemplate.update(
                "delete from batch.job_instance where tenant_id = ? and id = ?",
                fixture.tenantId(),
                fixture.jobInstanceId());
          }));
      assertThat(deleteStarted.await(5, TimeUnit.SECONDS)).isTrue();
      awaitBlocked(deletePid.get());

      allowCheckCommit.countDown();
      insert.get(10, TimeUnit.SECONDS);
      delete.get(10, TimeUnit.SECONDS);

      assertThat(jobInstanceCount(fixture.tenantId(), fixture.jobInstanceId())).isZero();
      assertThat(checkCount(fixture.tenantId(), fixture.jobInstanceId())).isEqualTo(1);
    } finally {
      allowCheckCommit.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("数据库直写先附加历史引用时，后续归档删除等待并保留历史 ID")
  void shouldKeepHistoricalReferenceWhenDirectSqlInsertWinsConcurrentDelete() throws Exception {
    JobFixture fixture = insertJobInstance(unique("direct-delete-after-write"));
    CountDownLatch checkInserted = new CountDownLatch(1);
    CountDownLatch allowCheckCommit = new CountDownLatch(1);
    CountDownLatch deleteStarted = new CountDownLatch(1);
    AtomicInteger deletePid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);

    Future<?> insert = executor.submit(() -> new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          insertDirectCheck(fixture.tenantId(), fixture.jobInstanceId());
          checkInserted.countDown();
          awaitLatch(allowCheckCommit);
        }));
    Future<?> delete = null;
    try {
      assertThat(checkInserted.await(5, TimeUnit.SECONDS)).isTrue();
      delete = executor.submit(() -> new TransactionTemplate(transactionManager)
          .executeWithoutResult(status -> {
            deletePid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
            deleteStarted.countDown();
            jdbcTemplate.update(
                "delete from batch.job_instance where tenant_id = ? and id = ?",
                fixture.tenantId(),
                fixture.jobInstanceId());
          }));
      assertThat(deleteStarted.await(5, TimeUnit.SECONDS)).isTrue();
      awaitBlocked(deletePid.get());

      allowCheckCommit.countDown();
      insert.get(10, TimeUnit.SECONDS);
      delete.get(10, TimeUnit.SECONDS);

      assertThat(jobInstanceCount(fixture.tenantId(), fixture.jobInstanceId())).isZero();
      assertThat(checkCount(fixture.tenantId(), fixture.jobInstanceId())).isEqualTo(1);
    } finally {
      allowCheckCommit.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("父实例删除先提交时不得再创建 DQ 历史引用")
  void shouldRejectCheckInsertWhenDeleteWins() throws Exception {
    JobFixture fixture = insertJobInstance(unique("delete-before-write"));
    CountDownLatch parentDeleted = new CountDownLatch(1);
    CountDownLatch allowDeleteCommit = new CountDownLatch(1);
    CountDownLatch insertStarted = new CountDownLatch(1);
    AtomicInteger insertPid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);

    Future<?> delete = executor.submit(() -> new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          jdbcTemplate.update(
              "delete from batch.job_instance where tenant_id = ? and id = ?",
              fixture.tenantId(),
              fixture.jobInstanceId());
          parentDeleted.countDown();
          awaitLatch(allowDeleteCommit);
        }));
    Future<?> insert = null;
    try {
      assertThat(parentDeleted.await(5, TimeUnit.SECONDS)).isTrue();
      insert = executor.submit(() -> new TransactionTemplate(transactionManager)
          .executeWithoutResult(status -> {
            insertPid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
            insertStarted.countDown();
            checkMapper.insert(check(fixture.tenantId(), fixture.jobInstanceId()));
          }));
      assertThat(insertStarted.await(5, TimeUnit.SECONDS)).isTrue();
      awaitBlocked(insertPid.get());

      allowDeleteCommit.countDown();
      delete.get(10, TimeUnit.SECONDS);
      Future<?> insertFuture = insert;
      assertThatThrownBy(() -> insertFuture.get(10, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class);

      assertThat(jobInstanceCount(fixture.tenantId(), fixture.jobInstanceId())).isZero();
      assertThat(checkCount(fixture.tenantId(), fixture.jobInstanceId())).isZero();
    } finally {
      allowDeleteCommit.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("数据库直写等待父删除提交后，由数据库拒绝孤儿历史引用")
  void shouldRejectDirectSqlInsertWhenDeleteWins() throws Exception {
    JobFixture fixture = insertJobInstance(unique("direct-delete-before-write"));
    CountDownLatch parentDeleted = new CountDownLatch(1);
    CountDownLatch allowDeleteCommit = new CountDownLatch(1);
    CountDownLatch insertStarted = new CountDownLatch(1);
    AtomicInteger insertPid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);

    Future<?> delete = executor.submit(() -> new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          jdbcTemplate.update(
              "delete from batch.job_instance where tenant_id = ? and id = ?",
              fixture.tenantId(),
              fixture.jobInstanceId());
          parentDeleted.countDown();
          awaitLatch(allowDeleteCommit);
        }));
    Future<?> insert = null;
    try {
      assertThat(parentDeleted.await(5, TimeUnit.SECONDS)).isTrue();
      insert = executor.submit(() -> new TransactionTemplate(transactionManager)
          .executeWithoutResult(status -> {
            insertPid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
            insertStarted.countDown();
            insertDirectCheck(fixture.tenantId(), fixture.jobInstanceId());
          }));
      assertThat(insertStarted.await(5, TimeUnit.SECONDS)).isTrue();
      awaitBlocked(insertPid.get());

      allowDeleteCommit.countDown();
      delete.get(10, TimeUnit.SECONDS);
      Future<?> insertFuture = insert;
      assertThatThrownBy(() -> insertFuture.get(10, TimeUnit.SECONDS))
          .satisfies(DataQualityCheckReferenceIntegrityIntegrationTest::assertDqConstraintViolation);

      assertThat(jobInstanceCount(fixture.tenantId(), fixture.jobInstanceId())).isZero();
      assertThat(checkCount(fixture.tenantId(), fixture.jobInstanceId())).isZero();
    } finally {
      allowDeleteCommit.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private JobFixture insertJobInstance(String tenantId) {
    String jobCode = unique("dq-job");
    Long jobDefinitionId = jdbcTemplate.queryForObject(
        """
        insert into batch.job_definition (
          tenant_id, job_code, job_name, job_type, schedule_type, timezone, retry_policy
        ) values (?, ?, 'DQ reference fixture', 'ATOMIC', 'MANUAL', 'Asia/Shanghai', 'NONE')
        returning id
        """,
        Long.class,
        tenantId,
        jobCode);
    Long jobInstanceId = jdbcTemplate.queryForObject(
        """
        insert into batch.job_instance (
          tenant_id, job_definition_id, job_code, instance_no, biz_date,
          trigger_type, instance_status, dedup_key
        ) values (?, ?, ?, ?, current_date, 'MANUAL', 'SUCCESS', ?) returning id
        """,
        Long.class,
        tenantId,
        jobDefinitionId,
        jobCode,
        unique("dq-instance"),
        unique("dq-dedup"));
    return new JobFixture(tenantId, jobInstanceId);
  }

  private static DataQualityCheckEntity check(String tenantId, Long jobInstanceId) {
    return DataQualityCheckEntity.builder()
        .tenantId(tenantId)
        .jobInstanceId(jobInstanceId)
        .ruleCode("dq-reference")
        .ruleType("TABLE_LEVEL")
        .severity("WARN")
        .status("PASS")
        .metricsJson("{}")
        .checkedAt(Instant.now())
        .build();
  }

  private int checkCount(String tenantId, Long jobInstanceId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from batch.data_quality_check where tenant_id = ? and job_instance_id = ?",
        Integer.class,
        tenantId,
        jobInstanceId);
  }

  private void insertDirectCheck(String tenantId, Long jobInstanceId) {
    jdbcTemplate.update(
        """
        insert into batch.data_quality_check (
          tenant_id, job_instance_id, rule_code, rule_type, severity, status, metrics_json
        ) values (?, ?, 'dq-direct-reference', 'TABLE_LEVEL', 'WARN', 'PASS', '{}'::jsonb)
        """,
        tenantId,
        jobInstanceId);
  }

  private int jobInstanceCount(String tenantId, Long jobInstanceId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from batch.job_instance where tenant_id = ? and id = ?",
        Integer.class,
        tenantId,
        jobInstanceId);
  }

  private void awaitBlocked(int pid) {
    Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> jdbcTemplate.queryForObject(
        "select count(*) from pg_stat_activity where pid = ? and wait_event_type = 'Lock'",
        Integer.class,
        pid) == 1);
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for transaction barrier");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for transaction barrier", exception);
    }
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  private static void assertDqConstraintViolation(Throwable throwable) {
    Throwable cause = throwable;
    while (cause != null && !(cause instanceof PSQLException)) {
      cause = cause.getCause();
    }
    assertThat(cause).isInstanceOf(PSQLException.class);
    PSQLException sqlException = (PSQLException) cause;
    assertThat(sqlException.getSQLState()).isEqualTo("23503");
    assertThat(sqlException.getServerErrorMessage().getConstraint())
        .isEqualTo("fk_dq_check_job_instance_reference");
  }

  private record JobFixture(String tenantId, Long jobInstanceId) {}
}
