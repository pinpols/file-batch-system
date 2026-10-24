package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.asset.AssetPartitionService;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionQueryService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.AssetPartitionMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared asset identity must never expose rows that another transaction cannot reference. */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("资产 ID 缓存的 PostgreSQL 提交可见性与回滚隔离")
class AssetPartitionCacheTransactionIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "ta";
  private static final LocalDate FIRST_DATE = LocalDate.of(2026, Month.OCTOBER, 16);

  @Autowired
  private AssetPartitionMapper mapper;

  @Autowired
  private ResultVersionQueryService resultVersionQueryService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private PlatformTransactionManager transactionManager;

  private AssetPartitionService service;
  private TransactionTemplate transactions;

  @BeforeEach
  void setUp() {
    service = new AssetPartitionService(mapper, resultVersionQueryService);
    transactions = new TransactionTemplate(transactionManager);
  }

  @Test
  @DisplayName("首个物化事务提交前并发调用等待数据库身份,不能使用尚未提交的缓存 ID")
  void shouldWaitForCommittedAssetIdentity_whenMaterializingConcurrently() throws Exception {
    String jobCode = uniqueJobCode();
    Materialization first = materialization(jobCode, FIRST_DATE, 701L);
    Materialization second = materialization(jobCode, FIRST_DATE.plusDays(1), 702L);
    CountDownLatch firstMaterialized = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    AtomicInteger firstBackend = new AtomicInteger();
    AtomicInteger secondBackend = new AtomicInteger();

    try (var executor = Executors.newFixedThreadPool(2)) {
      var firstWrite = executor.submit(() -> transactions.executeWithoutResult(status -> {
        firstBackend.set(backendPid());
        service.materializeEffectiveJobPartition(first.instance(), first.version());
        firstMaterialized.countDown();
        awaitLatch(releaseFirst);
      }));
      try {
        assertThat(firstMaterialized.await(15, TimeUnit.SECONDS)).isTrue();
        var secondWrite = executor.submit(() -> transactions.executeWithoutResult(status -> {
          secondBackend.set(backendPid());
          secondStarted.countDown();
          service.materializeEffectiveJobPartition(second.instance(), second.version());
        }));
        assertThat(secondStarted.await(15, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(15)).until(() -> secondWrite.isDone()
            || isBlockedBy(secondBackend.get(), firstBackend.get()));
        if (secondWrite.isDone()) {
          // Surface the actual FK violation from the original implementation.
          secondWrite.get(1, TimeUnit.SECONDS);
        }
        assertThat(secondWrite.isDone()).isFalse();
        releaseFirst.countDown();
        firstWrite.get(15, TimeUnit.SECONDS);
        secondWrite.get(15, TimeUnit.SECONDS);
        assertThat(partitionCount(jobCode)).isEqualTo(2);
        assertThat(assetCount(jobCode)).isOne();
      } finally {
        releaseFirst.countDown();
        firstWrite.get(15, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  @DisplayName("首次物化回滚后重新解析身份,不能复用已回滚的主键")
  void shouldReloadAssetIdentity_whenFirstMaterializationRollsBack() {
    String jobCode = uniqueJobCode();
    Materialization first = materialization(jobCode, FIRST_DATE, 703L);
    Materialization second = materialization(jobCode, FIRST_DATE.plusDays(1), 704L);
    transactions.executeWithoutResult(status -> {
      service.materializeEffectiveJobPartition(first.instance(), first.version());
      status.setRollbackOnly();
    });
    assertThat(assetCount(jobCode)).isZero();
    assertThat(partitionCount(jobCode)).isZero();

    transactions.executeWithoutResult(status ->
        service.materializeEffectiveJobPartition(second.instance(), second.version()));

    assertThat(assetCount(jobCode)).isOne();
    assertThat(partitionCount(jobCode)).isOne();
    assertThat(mapper.selectEffectiveJobPartition(TENANT, jobCode, second.instance().getBizDate().toString()))
        .isNotNull();
  }

  @Test
  @DisplayName("提交后的资产 ID 仍可复用缓存,后续物化不反复更新资产主数据")
  void shouldReuseCommittedAssetIdentity_whenMaterializingAgain() {
    String jobCode = uniqueJobCode();
    Materialization first = materialization(jobCode, FIRST_DATE, 705L);
    transactions.executeWithoutResult(status ->
        service.materializeEffectiveJobPartition(first.instance(), first.version()));
    String committedVersion = assetRowVersion(jobCode);

    transactions.executeWithoutResult(status ->
        service.materializeEffectiveJobPartition(first.instance(), first.version()));

    assertThat(assetRowVersion(jobCode)).isEqualTo(committedVersion);
    assertThat(partitionCount(jobCode)).isOne();
  }

  private Materialization materialization(String jobCode, LocalDate bizDate, long instanceId) {
    String businessKey = "job:" + jobCode + ":" + bizDate;
    Long versionId = jdbcTemplate.queryForObject(
        """
        insert into batch.result_version (
          tenant_id, business_key, version_no, job_instance_id, status, effective_at,
          payload_storage, payload_json, generated_at, generated_by, promotion_policy
        ) values (?, ?, 1, ?, 'EFFECTIVE', current_timestamp,
          'INLINE_JSON', '{}'::jsonb, current_timestamp, 'it', 'AUTO_LATEST')
        returning id
        """, Long.class, TENANT, businessKey, instanceId);
    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setTenantId(TENANT);
    instance.setJobCode(jobCode);
    instance.setBizDate(bizDate);
    instance.setId(instanceId);
    ResultVersionEntity version = ResultVersionEntity.builder()
        .id(versionId)
        .tenantId(TENANT)
        .businessKey(businessKey)
        .versionNo(1)
        .jobInstanceId(instanceId)
        .status("EFFECTIVE")
        .effectiveAt(Instant.parse("2026-10-16T00:00:00Z"))
        .payloadStorage("INLINE_JSON")
        .payloadJson("{}")
        .build();
    return new Materialization(instance, version);
  }

  private String uniqueJobCode() {
    return "CACHE_TX_" + UUID.randomUUID().toString().replace("-", "");
  }

  private int backendPid() {
    return jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class);
  }

  private boolean isBlockedBy(int waitingBackend, int blockingBackend) {
    return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
        "select ? = any(pg_blocking_pids(?))", Boolean.class, blockingBackend, waitingBackend));
  }

  private long assetCount(String jobCode) {
    return jdbcTemplate.queryForObject(
        "select count(*) from batch.data_asset where tenant_id = ? and asset_code = ?",
        Long.class, TENANT, jobCode);
  }

  private long partitionCount(String jobCode) {
    return jdbcTemplate.queryForObject(
        "select count(*) from batch.asset_partition where tenant_id = ? and asset_code = ?",
        Long.class, TENANT, jobCode);
  }

  private String assetRowVersion(String jobCode) {
    return jdbcTemplate.queryForObject(
        "select xmin::text from batch.data_asset where tenant_id = ? and asset_code = ?",
        String.class, TENANT, jobCode);
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while controlling transaction order", exception);
    }
  }

  private record Materialization(JobInstanceEntity instance, ResultVersionEntity version) {}
}
