package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;

import io.github.pinpols.batch.console.application.config.ConfigCacheInvalidationRelay;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.TenantQuotaPolicySpec;
import io.github.pinpols.batch.console.infrastructure.config.RedisConfigInvalidationStore;
import io.github.pinpols.batch.e2e.apps.E2eConsoleImportApplication;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigInvalidationSubscriber;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.worker.imports.runtime.ImportWorkerLoop;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@E2eSpringBootTest(
    classes = E2eConsoleImportApplication.class,
    properties = "batch.orchestrator.config-cache.local-positive-ttl-millis=120000")
@DisplayName("配置缓存对账与修订号推进顺序")
class ConfigInvalidationReconcileOrderingE2eIT extends AbstractIntegrationTest {
  @Autowired
  private ConsoleTenantConfigInitApplicationService initService;

  @Autowired
  private ConsoleConfigCacheInvalidationService invalidation;

  @Autowired
  private ConfigCacheInvalidationRelay relay;

  @Autowired
  private OrchestratorConfigCacheService cache;

  @Autowired
  private OrchestratorConfigInvalidationSubscriber subscriber;

  @Autowired
  private JdbcTemplate jdbc;

  @MockitoBean
  private ImportWorkerLoop importWorkerLoop;

  @MockitoSpyBean
  private RedisConfigInvalidationStore store;

  @Test
  @DisplayName("丢失广播时，对账观察到全局版本后读取已失效的配额")
  void reconcileMustSeeScopedRevisionBeforeGlobalRevision() throws Exception {
    String tenant = "quota-order-" + UUID.randomUUID().toString().substring(0, 8);
    jdbc.update(
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')",
        tenant,
        tenant);
    TenantQuotaPolicySpec quota = new TenantQuotaPolicySpec();
    quota.setPolicyCode("quota");
    quota.setMaxRunningJobsPerTenant(10);
    quota.setEnabled(true);
    TenantConfigBatchInitRequest seed = new TenantConfigBatchInitRequest();
    seed.setTargetTenantIds(List.of(tenant));
    seed.setStrict(true);
    seed.setQuotaPolicies(List.of(quota));
    assertThat(initService.batchInit(seed, "integration-test", "seed-" + tenant).failureTenants())
        .isZero();
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select count(*) from batch.config_cache_invalidation_outbox where tenant_id = ?",
                Integer.class,
                tenant))
            .isZero());
    subscriber.reconcileRevisionGap();
    cache.evictAllLocal();
    assertThat(cache.findEnabledQuotaPolicy(tenant).maxRunningJobsPerTenant()).isEqualTo(10);
    relay.stop();
    jdbc.update(
        "update batch.tenant_quota_policy set max_running_jobs_per_tenant = 20 where tenant_id = ?",
        tenant);

    CountDownLatch globalAdvanced = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    doAnswer(invocation -> {
          Object revision = invocation.callRealMethod();
          globalAdvanced.countDown();
          if (!resume.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("等待对账超时");
          }
          return revision;
        })
        .when(store)
        .nextGlobalRevision();
    doNothing().when(store).publish(any());
    try (var executor = Executors.newSingleThreadExecutor()) {
      Future<?> publication = executor.submit(() -> invalidation.evictQuotaPolicies(tenant));
      try {
        assertThat(globalAdvanced.await(10, TimeUnit.SECONDS)).isTrue();
        subscriber.reconcileRevisionGap();
        assertThat(cache.findEnabledQuotaPolicy(tenant).maxRunningJobsPerTenant())
            .isEqualTo(20);
      } finally {
        resume.countDown();
      }
      publication.get(10, TimeUnit.SECONDS);
      subscriber.reconcileRevisionGap();
      assertThat(cache.findEnabledQuotaPolicy(tenant).maxRunningJobsPerTenant()).isEqualTo(20);
    }
  }
}
