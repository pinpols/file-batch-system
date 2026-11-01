package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;

import com.github.dockerjava.api.DockerClient;
import io.github.pinpols.batch.common.redis.BatchRedisKeys;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.application.config.ConsoleConfigSyncApplicationService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncImportRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.InitMode;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.TenantQuotaPolicySpec;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncImportResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.TenantInitResult;
import io.github.pinpols.batch.console.infrastructure.config.RedisConfigInvalidationStore;
import io.github.pinpols.batch.e2e.apps.E2eConsoleImportApplication;
import io.github.pinpols.batch.orchestrator.domain.entity.TenantQuotaPolicyEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigInvalidationSubscriber;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.TestContainerLabels;
import io.github.pinpols.batch.worker.imports.runtime.ImportWorkerLoop;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@E2eSpringBootTest(
    classes = E2eConsoleImportApplication.class,
    properties = {
      "batch.orchestrator.config-cache.local-positive-ttl-millis=120000",
      "spring.data.redis.timeout=2s",
      "spring.data.redis.connect-timeout=2s"
    })
@DisplayName("配置提交后缓存失效发布故障的持久恢复")
class ConfigCacheInvalidationRecoveryE2eIT extends AbstractIntegrationTest {

  @Autowired
  private ConsoleTenantConfigInitApplicationService initService;

  @Autowired
  private ConsoleConfigSyncApplicationService syncService;

  @Autowired
  private OrchestratorConfigCacheService cache;

  @Autowired
  private OrchestratorConfigInvalidationSubscriber subscriber;

  @Autowired
  private StringRedisTemplate redis;

  @Autowired
  private JdbcTemplate jdbc;

  @MockitoBean
  private ImportWorkerLoop importWorkerLoop;

  @MockitoSpyBean
  private RedisConfigInvalidationStore invalidationStore;

  @Test
  @DisplayName("配额版本写入失败后，恢复应收敛到已提交配额")
  void shouldRecoverCommittedQuota_whenKeyRevisionUpdateFails() {
    String tenant = createTenantWithCachedQuota();
    String globalKey = BatchRedisKeys.configInvalidationGlobalRevision();
    String quotaRevisionKey = BatchRedisKeys.configInvalidationKeyRevision(
        tenant, "tenant-quota-policy", "enabled-first");
    long previousGlobal = Long.parseLong(redis.opsForValue().get(globalKey));
    String previousQuotaRevision = redis.opsForValue().get(quotaRevisionKey);
    assertThat(previousQuotaRevision).isNotNull();

    AtomicInteger attempts = new AtomicInteger();
    AtomicBoolean failRevision = new AtomicBoolean(true);
    doAnswer(invocation -> {
          attempts.incrementAndGet();
          if (failRevision.get()) {
            throw new RedisConnectionFailureException("injected key revision failure");
          }
          return invocation.callRealMethod();
        })
        .when(invalidationStore)
        .nextKeyRevision(tenant, "tenant-quota-policy", "enabled-first");
    ConfigSyncImportRequest request = quotaUpdate(tenant);
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try {
      ConfigSyncImportResponse response = syncService.importBundle(request);
      String quotaSql = """
          select max_running_jobs_per_tenant from batch.tenant_quota_policy
          where tenant_id = ? and policy_code = ?
          """;
      Integer storedLimit = jdbc.queryForObject(quotaSql, Integer.class, tenant, "quota");
      assertThat(storedLimit).isEqualTo(20);
      String receiptSql = """
          select result_json::text from batch.config_sync_target_receipt
          where audit_tenant_id = ? and log_id = ? and tenant_id = ?
          """;
      String receiptJson =
          jdbc.queryForObject(receiptSql, String.class, tenant, response.syncLogId(), tenant);
      TenantInitResult receipt = JsonUtils.fromJsonStrict(receiptJson, TenantInitResult.class);
      assertThat(receipt.success()).isTrue();
      assertThat(receipt.quotaPolicies().updated()).isOne();
      String currentGlobal = redis.opsForValue().get(globalKey);
      String currentQuotaRevision = redis.opsForValue().get(quotaRevisionKey);
      assertThat(attempts.get()).isGreaterThanOrEqualTo(1);

      subscriber.reconcileRevisionGap();
      cache.evictAllLocal();
      assertThat(cache.findEnabledQuotaPolicy(tenant).maxRunningJobsPerTenant()).isEqualTo(10);
      failRevision.set(false);
      await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
        TenantQuotaPolicyEntity recovered = cache.findEnabledQuotaPolicy(tenant);
        assertThat(recovered.maxRunningJobsPerTenant())
            .as(
                "全局版本 %s→%s，配额版本 %s→%s",
                previousGlobal, currentGlobal, previousQuotaRevision, currentQuotaRevision)
            .isEqualTo(20);
      });
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  @Test
  @Timeout(90)
  @DisplayName("Redis 整段不可用时提交的配额，恢复后应清除旧共享缓存")
  void shouldRecoverCommittedQuota_whenRedisWasUnavailable() {
    assertThat(Boolean.getBoolean("batch.testcontainers.reuse"))
        .as("故障注入不允许使用复用容器")
        .isFalse();
    String tenant = createTenantWithCachedQuota();
    String globalKey = BatchRedisKeys.configInvalidationGlobalRevision();
    String quotaRevisionKey = BatchRedisKeys.configInvalidationKeyRevision(
        tenant, "tenant-quota-policy", "enabled-first");
    String previousGlobal = redis.opsForValue().get(globalKey);
    String previousQuotaRevision = redis.opsForValue().get(quotaRevisionKey);
    String containerId = REDIS.getContainerId();
    DockerClient docker = REDIS.getDockerClient();
    Map<String, String> labels =
        docker.inspectContainerCmd(containerId).exec().getConfig().getLabels();
    assertThat(labels)
        .containsEntry(TestContainerLabels.OWNER, TestContainerLabels.OWNER_VALUE)
        .doesNotContainKey(TestContainerLabels.LOCAL_REUSE);
    ConfigSyncImportRequest request = quotaUpdate(tenant);
    ConfigSyncImportResponse response;
    boolean pauseAttempted = false;
    Throwable primaryFailure = null;
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    try {
      assertThat(docker.inspectContainerCmd(containerId).exec().getState().getPaused())
          .isFalse();
      pauseAttempted = true;
      docker.pauseContainerCmd(containerId).exec();
      assertThat(docker.inspectContainerCmd(containerId).exec().getState().getPaused())
          .isTrue();
      response = syncService.importBundle(request);
    } catch (RuntimeException | Error failure) {
      primaryFailure = failure;
      throw failure;
    } finally {
      try {
        if (pauseAttempted) {
          try {
            restoreRedisContainer(docker, containerId);
          } catch (RuntimeException restoreFailure) {
            if (primaryFailure != null) {
              primaryFailure.addSuppressed(restoreFailure);
            } else {
              throw restoreFailure;
            }
          }
        }
      } finally {
        RequestContextHolder.resetRequestAttributes();
      }
    }
    await()
        .atMost(Duration.ofSeconds(30))
        .ignoreExceptions()
        .untilAsserted(() -> assertThat(redis.opsForValue().get(globalKey)).isNotNull());
    String quotaSql = """
        select max_running_jobs_per_tenant from batch.tenant_quota_policy
        where tenant_id = ? and policy_code = ?
        """;
    Integer storedLimit = jdbc.queryForObject(quotaSql, Integer.class, tenant, "quota");
    assertThat(storedLimit).isEqualTo(20);
    String receiptSql = """
        select result_json::text from batch.config_sync_target_receipt
        where audit_tenant_id = ? and log_id = ? and tenant_id = ?
        """;
    String receiptJson =
        jdbc.queryForObject(receiptSql, String.class, tenant, response.syncLogId(), tenant);
    TenantInitResult receipt = JsonUtils.fromJsonStrict(receiptJson, TenantInitResult.class);
    assertThat(receipt.success()).isTrue();
    assertThat(receipt.quotaPolicies().updated()).isOne();
    String currentGlobal = redis.opsForValue().get(globalKey);
    String currentQuotaRevision = redis.opsForValue().get(quotaRevisionKey);
    subscriber.reconcileRevisionGap();
    cache.evictAllLocal();
    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
      TenantQuotaPolicyEntity recovered = cache.findEnabledQuotaPolicy(tenant);
      assertThat(recovered.maxRunningJobsPerTenant())
          .as(
              "Redis 恢复后全局版本 %s→%s，配额版本 %s→%s",
              previousGlobal, currentGlobal, previousQuotaRevision, currentQuotaRevision)
          .isEqualTo(20);
    });
  }

  private static void restoreRedisContainer(DockerClient docker, String containerId) {
    RuntimeException lastFailure = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        docker.unpauseContainerCmd(containerId).exec();
        return;
      } catch (RuntimeException failure) {
        if (lastFailure != null) {
          failure.addSuppressed(lastFailure);
        }
        lastFailure = failure;
        try {
          Boolean paused =
              docker.inspectContainerCmd(containerId).exec().getState().getPaused();
          if (Boolean.FALSE.equals(paused)) {
            return;
          }
        } catch (RuntimeException inspectionFailure) {
          failure.addSuppressed(inspectionFailure);
        }
      }
    }
    throw lastFailure;
  }

  private String createTenantWithCachedQuota() {
    String tenant =
        "quota-recovery-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String insertTenant = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
        """;
    jdbc.update(insertTenant, tenant, tenant);
    TenantConfigBatchInitRequest seed = new TenantConfigBatchInitRequest();
    seed.setTargetTenantIds(List.of(tenant));
    seed.setStrict(true);
    seed.setQuotaPolicies(List.of(quota(10)));
    assertThat(initService.batchInit(seed, "integration-test", "seed-" + tenant).failureTenants())
        .isZero();
    String pendingSql = """
        select count(*) from batch.config_cache_invalidation_outbox where tenant_id = ?
        """;
    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
      Integer pending = jdbc.queryForObject(pendingSql, Integer.class, tenant);
      assertThat(pending).isZero();
    });
    subscriber.reconcileRevisionGap();
    cache.evictAllLocal();
    TenantQuotaPolicyEntity original = cache.findEnabledQuotaPolicy(tenant);
    assertThat(original.maxRunningJobsPerTenant()).isEqualTo(10);
    return tenant;
  }

  private static ConfigSyncImportRequest quotaUpdate(String tenant) {
    ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
    bundle.setQuotaPolicies(List.of(quota(20)));
    ConfigSyncImportRequest request = new ConfigSyncImportRequest();
    request.setTenantId(tenant);
    request.setSourceEnv("failure-source");
    request.setTargetEnv("integration-test");
    request.setTargetTenantIds(List.of(tenant));
    request.setMode(InitMode.UPSERT);
    request.setBundle(bundle);
    return request;
  }

  private static TenantQuotaPolicySpec quota(int maxRunningJobs) {
    TenantQuotaPolicySpec spec = new TenantQuotaPolicySpec();
    spec.setPolicyCode("QUOTA");
    spec.setMaxRunningJobsPerTenant(maxRunningJobs);
    spec.setEnabled(true);
    return spec;
  }
}
