package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.outbox.OutboxChildRecordService;
import io.github.pinpols.batch.orchestrator.domain.entity.EventDeliveryLogEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.EventOutboxRetryEntity;
import io.github.pinpols.batch.orchestrator.mapper.OutboxEventMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
    properties = {
      "batch.outbox.archive.enabled=true",
      "batch.outbox.archive.published-retention-days=1",
      "batch.outbox.archive.batch-size=10",
      "batch.result-version.retention.enabled=false"
    })
@DisplayName("Outbox 强引用与归档生命周期真实 PostgreSQL 守护")
class OutboxReferenceIntegrityIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;
  private final OutboxEventMapper outboxEventMapper;
  private final OutboxChildRecordService outboxChildRecordService;
  private final PlatformTransactionManager transactionManager;

  @Autowired
  OutboxReferenceIntegrityIntegrationTest(
      JdbcTemplate jdbcTemplate,
      OutboxEventMapper outboxEventMapper,
      OutboxChildRecordService outboxChildRecordService,
      PlatformTransactionManager transactionManager) {
    this.jdbcTemplate = jdbcTemplate;
    this.outboxEventMapper = outboxEventMapper;
    this.outboxChildRecordService = outboxChildRecordService;
    this.transactionManager = transactionManager;
  }

  @Test
  @DisplayName("V306 在分区父表及其约束克隆上安装已验证的复合外键")
  void shouldInstallValidatedCompositeForeignKeysForPartitionedOutbox() {
    Integer validatedRootForeignKeys = jdbcTemplate.queryForObject(
        """
        select count(*)
          from pg_constraint
         where contype = 'f'
           and conname in (
             'fk_event_delivery_log_outbox_event_reference',
             'fk_event_outbox_retry_outbox_event_reference'
           )
           and convalidated
           and confdeltype = 'a'
        """,
        Integer.class);
    Integer unvalidatedForeignKeyClones = jdbcTemplate.queryForObject(
        """
        select count(*)
          from pg_constraint child_constraint
         where child_constraint.contype = 'f'
           and child_constraint.confrelid = 'batch.outbox_event'::regclass
           and not child_constraint.convalidated
           and (
             child_constraint.conname in (
               'fk_event_delivery_log_outbox_event_reference',
               'fk_event_outbox_retry_outbox_event_reference'
             )
             or child_constraint.conparentid in (
               select parent_constraint.oid
                 from pg_constraint parent_constraint
                where parent_constraint.conname in (
                  'fk_event_delivery_log_outbox_event_reference',
                  'fk_event_outbox_retry_outbox_event_reference'
                )
             )
           )
        """,
        Integer.class);

    assertThat(validatedRootForeignKeys).isEqualTo(2);
    assertThat(unvalidatedForeignKeyClones).isZero();
  }

  @Test
  @DisplayName("delivery 和 retry 子行只接受同租户的真实 Outbox 父事件")
  void shouldRejectMissingAndCrossTenantOutboxReferences() {
    String ownerTenant = unique("owner");
    String otherTenant = unique("other");
    Long outboxId = insertOldPublishedOutbox(ownerTenant);

    assertThatThrownBy(() -> insertDelivery(otherTenant, outboxId))
        .isInstanceOf(DataAccessException.class)
        .satisfies(this::assertForeignKeyViolation);
    assertThatThrownBy(() -> insertRetry(ownerTenant, outboxId + 999_999L))
        .isInstanceOf(DataAccessException.class)
        .satisfies(this::assertForeignKeyViolation);

    assertThat(count("batch.event_delivery_log", outboxId)).isZero();
    assertThat(count("batch.event_outbox_retry", outboxId + 999_999L)).isZero();
  }

  @Test
  @DisplayName("父 Outbox 事件被子行引用时禁止删除或修改身份")
  void shouldRejectDeletingOrRekeyingOutboxWithChildren() {
    String tenantId = unique("tenant");
    Long deliveryOutboxId = insertOldPublishedOutbox(tenantId);
    insertDelivery(tenantId, deliveryOutboxId);

    assertThatThrownBy(() -> deleteOutbox(tenantId, deliveryOutboxId))
        .isInstanceOf(DataAccessException.class)
        .satisfies(this::assertForeignKeyViolation);
    assertThatThrownBy(() -> rekeyOutbox(tenantId, deliveryOutboxId))
        .isInstanceOf(DataAccessException.class)
        .satisfies(this::assertForeignKeyViolation);

    assertThat(parentCount("batch.outbox_event", deliveryOutboxId)).isEqualTo(1);

    String retryTenantId = unique("retry-tenant");
    Long retryOutboxId = insertOldPublishedOutbox(retryTenantId);
    insertRetry(retryTenantId, retryOutboxId);
    assertThatThrownBy(() -> deleteOutbox(retryTenantId, retryOutboxId))
        .isInstanceOf(DataAccessException.class)
        .satisfies(this::assertForeignKeyViolation);
    assertThat(parentCount("batch.outbox_event", retryOutboxId)).isEqualTo(1);
  }

  @Test
  @DisplayName("归档锁定父事件时，迟到的 delivery 与 retry 审计等待后写入同一冷归档")
  void shouldPreserveLateChildrenWhenArchiveCopiesBeforeConcurrentWriter() throws Exception {
    String tenantId = unique("tenant");
    Long outboxId = insertOldPublishedOutbox(tenantId);
    Instant outboxCreatedAt = findCreatedAt(outboxId);
    CountDownLatch copiedToCold = new CountDownLatch(1);
    CountDownLatch allowHotDelete = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<?> archiveFuture = executor.submit(() -> new TransactionTemplate(transactionManager)
          .execute(status -> {
            List<Long> ids = outboxEventMapper.selectArchivableIds(
                "PUBLISHED", Instant.now().minus(Duration.ofDays(1)), 10);
            assertThat(ids).contains(outboxId);
            outboxEventMapper.archiveEventDeliveryLogsByOutboxIds(ids);
            outboxEventMapper.archiveEventOutboxRetriesByOutboxIds(ids);
            outboxEventMapper.archiveOutboxEventsByIds(ids);
            copiedToCold.countDown();
            awaitLatch(allowHotDelete);
            outboxEventMapper.deleteEventDeliveryLogsByOutboxIds(ids);
            outboxEventMapper.deleteEventOutboxRetriesByOutboxIds(ids);
            outboxEventMapper.deleteByIds(ids);
            return null;
          }));

      assertThat(copiedToCold.await(10, TimeUnit.SECONDS)).isTrue();
      Future<?> writerFuture = executor.submit(() -> {
        outboxChildRecordService.recordDelivery(delivery(tenantId, outboxId, outboxCreatedAt));
        outboxChildRecordService.recordRetry(retry(tenantId, outboxId, outboxCreatedAt));
      });

      assertThat(awaitChildInsertBlocked(Duration.ofSeconds(10))).isTrue();
      allowHotDelete.countDown();
      archiveFuture.get(10, TimeUnit.SECONDS);
      writerFuture.get(10, TimeUnit.SECONDS);

      assertThat(parentCount("batch.outbox_event", outboxId)).isZero();
      assertThat(count("batch.event_delivery_log", outboxId)).isZero();
      assertThat(count("batch.event_outbox_retry", outboxId)).isZero();
      assertThat(parentCount("archive.outbox_event_archive", outboxId)).isEqualTo(1);
      assertThat(count("archive.event_delivery_log_archive", outboxId)).isEqualTo(1);
      assertThat(count("archive.event_outbox_retry_archive", outboxId)).isEqualTo(1);
    } finally {
      allowHotDelete.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private Long insertOldPublishedOutbox(String tenantId) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.outbox_event(
          tenant_id, aggregate_type, aggregate_id, event_type, event_key, payload_json,
          publish_status, created_at, updated_at
        ) values (?, 'TEST', 1, 'TEST', ?, '{}'::jsonb, 'PUBLISHED', now() - interval '3 days', now())
        returning id
        """,
        Long.class,
        tenantId,
        unique("event"));
  }

  private void insertDelivery(String tenantId, Long outboxId) {
    jdbcTemplate.update(
        """
        insert into batch.event_delivery_log(
          tenant_id, outbox_event_id, outbox_event_created_at, event_type, event_key,
          target_topic, delivery_status
        ) values (?, ?, ?, 'TEST', ?, 'test-topic', 'PUBLISHED')
        """,
        tenantId,
        outboxId,
        Timestamp.from(findCreatedAt(outboxId)),
        unique("delivery"));
  }

  private void insertRetry(String tenantId, Long outboxId) {
    jdbcTemplate.update(
        """
        insert into batch.event_outbox_retry(
          tenant_id, outbox_event_id, outbox_event_created_at, event_key, retry_status
        ) values (?, ?, ?, ?, 'FAILED')
        """,
        tenantId,
        outboxId,
        Timestamp.from(findCreatedAtOrDefault(outboxId)),
        unique("retry"));
  }

  private Instant findCreatedAt(Long outboxId) {
    return jdbcTemplate.queryForObject(
        "select created_at from batch.outbox_event where id = ?", Instant.class, outboxId);
  }

  private boolean awaitChildInsertBlocked(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      Boolean blocked = jdbcTemplate.queryForObject(
          """
          select exists (
            select 1 from pg_stat_activity
            where wait_event_type = 'Lock'
              and lower(query) like '%insert into batch.event_delivery_log%'
          )
          """,
          Boolean.class);
      if (Boolean.TRUE.equals(blocked)) {
        return true;
      }
      Thread.sleep(25);
    }
    return false;
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting to continue Outbox archive test");
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while holding Outbox archive transaction", ex);
    }
  }

  private static EventDeliveryLogEntity delivery(
      String tenantId, Long outboxId, Instant outboxCreatedAt) {
    EventDeliveryLogEntity entity = new EventDeliveryLogEntity();
    entity.setTenantId(tenantId);
    entity.setOutboxEventId(outboxId);
    entity.setOutboxEventCreatedAt(outboxCreatedAt);
    entity.setEventType("TEST");
    entity.setEventKey(unique("late-delivery"));
    entity.setTargetTopic("test-topic");
    entity.setDeliveryStatus("PUBLISHED");
    entity.setDeliveryAttempt(1);
    return entity;
  }

  private static EventOutboxRetryEntity retry(
      String tenantId, Long outboxId, Instant outboxCreatedAt) {
    EventOutboxRetryEntity entity = new EventOutboxRetryEntity();
    entity.setTenantId(tenantId);
    entity.setOutboxEventId(outboxId);
    entity.setOutboxEventCreatedAt(outboxCreatedAt);
    entity.setEventKey(unique("late-retry"));
    entity.setPublishAttempt(1);
    entity.setRetryStatus("FAILED");
    return entity;
  }

  private Instant findCreatedAtOrDefault(Long outboxId) {
    return jdbcTemplate.query(
        "select created_at from batch.outbox_event where id = ?",
        resultSet -> resultSet.next() ? resultSet.getTimestamp(1).toInstant() : Instant.now(),
        outboxId);
  }

  private void deleteOutbox(String tenantId, Long id) {
    jdbcTemplate.update(
        "delete from batch.outbox_event where tenant_id = ? and id = ?", tenantId, id);
  }

  private void rekeyOutbox(String tenantId, Long id) {
    jdbcTemplate.update(
        "update batch.outbox_event set tenant_id = ? where tenant_id = ? and id = ?",
        unique("rekey"),
        tenantId,
        id);
  }

  private void assertForeignKeyViolation(Throwable failure) {
    Throwable current = failure;
    while (current != null && !(current instanceof SQLException)) {
      current = current.getCause();
    }
    assertThat(current).isInstanceOf(SQLException.class);
    assertThat(((SQLException) current).getSQLState()).isEqualTo("23503");
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  private int count(String table, Long outboxId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from " + table + " where outbox_event_id = ?", Integer.class, outboxId);
  }

  private int parentCount(String table, Long id) {
    return jdbcTemplate.queryForObject(
        "select count(*) from " + table + " where id = ?", Integer.class, id);
  }
}
