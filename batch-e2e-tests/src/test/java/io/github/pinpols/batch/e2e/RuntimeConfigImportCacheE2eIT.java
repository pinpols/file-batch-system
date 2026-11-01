package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pinpols.batch.common.redis.BatchRedisKeys;
import io.github.pinpols.batch.common.redis.BatchRedisNamespace;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.application.config.ConsoleConfigSyncApplicationService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncImportRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.InitMode;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncImportResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.TenantInitResult;
import io.github.pinpols.batch.e2e.apps.E2eConsoleImportApplication;
import io.github.pinpols.batch.e2e.apps.E2eReportRecoveryOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.config.OrchestratorConfigCacheProperties;
import io.github.pinpols.batch.orchestrator.domain.view.JobDefinitionView;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigInvalidationSubscriber;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigMappers;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorRedisSupport;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.worker.imports.runtime.ImportWorkerLoop;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@E2eSpringBootTest(
    classes = E2eConsoleImportApplication.class,
    properties = {
      "batch.orchestrator.config-cache.local-positive-ttl-millis=120000",
      "batch.orchestrator.config-cache.reconcile-interval-millis=3600000"
    })
@DisplayName("配置同步提交后两个独立调度缓存消费 Redis 失效事件")
class RuntimeConfigImportCacheE2eIT extends AbstractIntegrationTest {

  private static final Logger log = LoggerFactory.getLogger(RuntimeConfigImportCacheE2eIT.class);
  private static final String TENANT = "t1";
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Autowired
  private ConsoleTenantConfigInitApplicationService initService;

  @Autowired
  private ConsoleConfigSyncApplicationService syncService;

  @Autowired
  private OrchestratorConfigCacheService firstCache;

  @Autowired
  private OrchestratorRedisSupport redisSupport;

  @Autowired
  private OrchestratorConfigMappers configMappers;

  @Autowired
  private OrchestratorConfigCacheProperties cacheProperties;

  @Autowired
  private BatchRedisNamespace namespace;

  @Autowired
  private StringRedisTemplate redis;

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private ApplicationContext context;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Autowired
  @Qualifier("dataSource")
  private DataSource platformDataSource;

  @MockitoBean
  private ImportWorkerLoop importWorkerLoop;

  @Test
  @DisplayName("目标配置与成功回执提交后，两份已预热的近缓存均读取新版本")
  void shouldInvalidateBothLocalCachesAfterTargetReceiptCommits() {
    String insertCalendar = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)
        values (?, 'default_calendar', 'Default calendar', 'Asia/Shanghai', true)
        on conflict (tenant_id, calendar_code) do nothing
        """;
    jdbc.update(insertCalendar, TENANT);
    String jobCode =
        "CACHE_SYNC_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    TenantConfigBatchInitRequest seed = new TenantConfigBatchInitRequest();
    seed.setTargetTenantIds(List.of(TENANT));
    seed.setStrict(true);
    seed.setJobDefinitions(List.of(job(jobCode, "导入前")));
    assertThat(
            initService.batchInit(seed, "integration-test", "seed-" + jobCode).failureTenants())
        .isZero();

    OrchestratorConfigCacheService secondCache = new OrchestratorConfigCacheService(
        redisSupport, configMappers, cacheProperties, context.getBeanProvider(MeterRegistry.class));
    OrchestratorConfigInvalidationSubscriber secondSubscriber =
        new OrchestratorConfigInvalidationSubscriber(
            redis, namespace, secondCache, context.getBeanProvider(MeterRegistry.class));
    var factory = context.getAutowireCapableBeanFactory();
    factory.initializeBean(secondSubscriber, "secondConfigInvalidationSubscriber");
    try {
      String channel = namespace.channel("batch:config:invalidation");
      await().atMost(Duration.ofSeconds(10)).until(() -> {
        Long listeners = redis.convertAndSend(channel, "");
        return listeners != null && listeners >= 2;
      });
      assertThat(firstCache.findEnabledJobDefinition(TENANT, jobCode).jobName()).isEqualTo("导入前");
      assertThat(secondCache.findEnabledJobDefinition(TENANT, jobCode).jobName())
          .isEqualTo("导入前");

      ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
      bundle.setJobDefinitions(List.of(job(jobCode, "导入后")));
      ConfigSyncImportRequest request = new ConfigSyncImportRequest();
      request.setTenantId(TENANT);
      request.setSourceEnv("source-" + jobCode);
      request.setTargetEnv("integration-test");
      request.setTargetTenantIds(List.of(TENANT));
      request.setMode(InitMode.UPSERT);
      request.setBundle(bundle);
      RequestContextHolder.setRequestAttributes(
          new ServletRequestAttributes(new MockHttpServletRequest()));
      ConfigSyncImportResponse response = syncService.importBundle(request);
      assertThat(response.result().failureTenants()).isZero();
      assertThat(response.result().results())
          .singleElement()
          .satisfies(result -> assertThat(result.jobDefinitions().updated()).isOne());
      String jobNameSql = """
          select job_name from batch.job_definition where tenant_id = ? and job_code = ?
          """;
      String committedJobName = jdbc.queryForObject(jobNameSql, String.class, TENANT, jobCode);
      assertThat(committedJobName).isEqualTo("导入后");
      String receiptSql = """
          select result_json::text from batch.config_sync_target_receipt
          where audit_tenant_id = ? and log_id = ? and tenant_id = ?
          """;
      String receiptJson =
          jdbc.queryForObject(receiptSql, String.class, TENANT, response.syncLogId(), TENANT);
      TenantInitResult receipt = JsonUtils.fromJsonStrict(receiptJson, TenantInitResult.class);
      assertThat(receipt.tenantId()).isEqualTo(TENANT);
      assertThat(receipt.success()).isTrue();
      assertThat(receipt.jobDefinitions().updated()).isOne();
      await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
        JobDefinitionView first = firstCache.findEnabledJobDefinition(TENANT, jobCode);
        JobDefinitionView second = secondCache.findEnabledJobDefinition(TENANT, jobCode);
        assertThat(first.jobName()).isEqualTo("导入后");
        assertThat(second.jobName()).isEqualTo("导入后");
      });
    } finally {
      RequestContextHolder.resetRequestAttributes();
      factory.destroyBean(secondSubscriber);
    }
  }

  @Test
  @DisplayName("配置导入后两个独立调度进程更新目标租户缓存，不改变其他租户")
  void shouldInvalidateIndependentOrchestratorCachesForImportedTenant() throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String targetTenant = "cache-a-" + suffix;
    String otherTenant = "cache-b-" + suffix;
    String jobCode = "CACHE_PROCESS_" + suffix;
    String insertCalendar = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)
        values (?, 'default_calendar', '测试日历', 'Asia/Shanghai', true)
        """;
    for (String tenant : List.of(targetTenant, otherTenant)) {
      jdbc.update(insertCalendar, tenant);
      JobDefinitionSpec initialJob = job(jobCode, "进程缓存测试");
      initialJob.setPriority(tenant.equals(targetTenant) ? 2 : 4);
      TenantConfigBatchInitRequest seed = new TenantConfigBatchInitRequest();
      seed.setTargetTenantIds(List.of(tenant));
      seed.setStrict(true);
      seed.setJobDefinitions(List.of(initialJob));
      assertThat(initService.batchInit(seed, "integration-test", "seed-" + tenant).failureTenants())
          .isZero();
    }

    // 等待准备数据的持久化失效意图完成，避免把准备阶段的事件当作导入证据。
    String pendingSql = """
        select count(*) from batch.config_cache_invalidation_outbox where tenant_id in (?, ?)
        """;
    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
      Integer pending = jdbc.queryForObject(pendingSql, Integer.class, targetTenant, otherTenant);
      assertThat(pending).isZero();
    });
    String channel = namespace.channel("batch:config:invalidation");
    Long existingSubscribers = redis.convertAndSend(channel, "");
    assertThat(existingSubscribers).isNotNull();

    try (OrchestratorChild first = startOrchestratorChild(suffix + "-1");
        OrchestratorChild second = startOrchestratorChild(suffix + "-2")) {
      assertThat(first.process().pid()).isNotEqualTo(second.process().pid());
      assertThat(first.process().pid()).isNotEqualTo(ProcessHandle.current().pid());
      assertThat(second.process().pid()).isNotEqualTo(ProcessHandle.current().pid());
      assertThat(first.port()).isNotEqualTo(second.port());
      await().atMost(Duration.ofSeconds(15)).until(() -> {
        Long subscribers = redis.convertAndSend(channel, "");
        return subscribers != null && subscribers >= existingSubscribers + 2;
      });
      String rawRevision =
          redis.opsForValue().get(BatchRedisKeys.configInvalidationGlobalRevision());
      long seedRevision = rawRevision == null ? 0 : Long.parseLong(rawRevision);
      for (OrchestratorChild child : List.of(first, second)) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
          double applied =
              child.metric("batch.orchestrator.config.invalidation.applied_revision", "");
          assertThat(applied).isGreaterThanOrEqualTo(seedRevision);
        });
      }

      long warmedAt = System.nanoTime();
      for (OrchestratorChild child : List.of(first, second)) {
        String cacheHits = "?tag=cache%3Aorchestrator.config.job-definition.local&tag=result%3Ahit";
        double hitsBefore = child.metric("cache.gets", cacheHits);
        assertThat(child.priority(targetTenant, jobCode)).isEqualTo(2);
        assertThat(child.priority(otherTenant, jobCode)).isEqualTo(4);
        assertThat(child.priority(targetTenant, jobCode)).isEqualTo(2);
        assertThat(child.priority(otherTenant, jobCode)).isEqualTo(4);
        double hitsAfter = child.metric("cache.gets", cacheHits);
        assertThat(hitsAfter - hitsBefore).isGreaterThanOrEqualTo(2);
      }
      String eventMetric = "batch.orchestrator.config.invalidation.event.total";
      String appliedTag = "?tag=result%3Aapplied";
      double firstEvents = first.metric(eventMetric, appliedTag);
      double secondEvents = second.metric(eventMetric, appliedTag);

      JobDefinitionSpec updatedJob = job(jobCode, "进程缓存测试");
      updatedJob.setPriority(7);
      ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
      bundle.setJobDefinitions(List.of(updatedJob));
      ConfigSyncImportRequest request = new ConfigSyncImportRequest();
      request.setTenantId(targetTenant);
      request.setSourceEnv("source-" + suffix);
      request.setTargetEnv("integration-test");
      request.setTargetTenantIds(List.of(targetTenant));
      request.setMode(InitMode.UPSERT);
      request.setBundle(bundle);
      RequestContextHolder.setRequestAttributes(
          new ServletRequestAttributes(new MockHttpServletRequest()));
      try {
        ConfigSyncImportResponse response = syncService.importBundle(request);
        assertThat(response.result().failureTenants()).isZero();
        assertThat(response.result().results()).singleElement().satisfies(result -> {
          assertThat(result.tenantId()).isEqualTo(targetTenant);
          assertThat(result.jobDefinitions().updated()).isOne();
        });
        String receiptSql = """
            select result_json::text from batch.config_sync_target_receipt
            where audit_tenant_id = ? and log_id = ? and tenant_id = ?
            """;
        String receiptJson = jdbc.queryForObject(
            receiptSql, String.class, targetTenant, response.syncLogId(), targetTenant);
        TenantInitResult receipt = JsonUtils.fromJsonStrict(receiptJson, TenantInitResult.class);
        assertThat(receipt.tenantId()).isEqualTo(targetTenant);
        assertThat(receipt.success()).isTrue();
        assertThat(receipt.jobDefinitions().updated()).isOne();
      } finally {
        RequestContextHolder.resetRequestAttributes();
      }
      String prioritySql = """
          select priority from batch.job_definition where tenant_id = ? and job_code = ?
          """;
      Integer targetPriority =
          jdbc.queryForObject(prioritySql, Integer.class, targetTenant, jobCode);
      Integer otherPriority = jdbc.queryForObject(prioritySql, Integer.class, otherTenant, jobCode);
      assertThat(targetPriority).isEqualTo(7);
      assertThat(otherPriority).isEqualTo(4);
      await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
        assertThat(first.metric(eventMetric, appliedTag)).isGreaterThan(firstEvents);
        assertThat(second.metric(eventMetric, appliedTag)).isGreaterThan(secondEvents);
        assertThat(first.priority(targetTenant, jobCode)).isEqualTo(7);
        assertThat(second.priority(targetTenant, jobCode)).isEqualTo(7);
        assertThat(first.priority(otherTenant, jobCode)).isEqualTo(4);
        assertThat(second.priority(otherTenant, jobCode)).isEqualTo(4);
      });
      assertThat(Duration.ofNanos(System.nanoTime() - warmedAt))
          .isLessThan(Duration.ofSeconds(120));
      double firstApplied = first.metric(eventMetric, appliedTag);
      double secondApplied = second.metric(eventMetric, appliedTag);
      log.info(
          "配置导入失效事件：进程={} 端口={} 事件数={}->{} 日志={}；进程={} 端口={} 事件数={}->{} 日志={}",
          first.process().pid(), first.port(), firstEvents, firstApplied, first.log(),
          second.process().pid(), second.port(), secondEvents, secondApplied, second.log());
    }
  }

  @Test
  @DisplayName("重复读事务保持原数据库快照，导入后的普通读取保持新配置")
  void shouldReadTransactionSnapshotAfterConfigImport() throws Exception {
    String insertCalendar = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)
        values (?, 'default_calendar', 'Default calendar', 'Asia/Shanghai', true)
        on conflict (tenant_id, calendar_code) do nothing
        """;
    jdbc.update(insertCalendar, TENANT);
    String code = "SNAPSHOT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    TenantConfigBatchInitRequest seed = new TenantConfigBatchInitRequest();
    seed.setTargetTenantIds(List.of(TENANT));
    seed.setStrict(true);
    seed.setJobDefinitions(List.of(job(code, "旧快照")));
    assertThat(initService.batchInit(seed, "integration-test", "seed-" + code).failureTenants())
        .isZero();

    CountDownLatch snapshotReady = new CountDownLatch(1);
    CountDownLatch importFinished = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    try {
      Future<JobDefinitionView> inFlight = executor.submit(() -> {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction.execute(status -> {
          String selectName = """
              select job_name from batch.job_definition where tenant_id = ? and job_code = ?
              """;
          String snapshotName = jdbc.queryForObject(selectName, String.class, TENANT, code);
          assertThat(snapshotName).isEqualTo("旧快照");
          snapshotReady.countDown();
          try {
            if (!importFinished.await(30, TimeUnit.SECONDS)) {
              throw new IllegalStateException("配置导入未完成");
            }
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待配置导入时中断", exception);
          }
          return firstCache.findEnabledJobDefinition(TENANT, code);
        });
      });
      assertThat(snapshotReady.await(30, TimeUnit.SECONDS)).isTrue();

      ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
      bundle.setJobDefinitions(List.of(job(code, "新配置")));
      ConfigSyncImportRequest request = new ConfigSyncImportRequest();
      request.setTenantId(TENANT);
      request.setSourceEnv("snapshot-" + code);
      request.setTargetEnv("integration-test");
      request.setTargetTenantIds(List.of(TENANT));
      request.setMode(InitMode.UPSERT);
      request.setBundle(bundle);
      RequestContextHolder.setRequestAttributes(
          new ServletRequestAttributes(new MockHttpServletRequest()));
      ConfigSyncImportResponse response = syncService.importBundle(request);
      assertThat(response.result().results())
          .singleElement()
          .satisfies(result -> assertThat(result.jobDefinitions().updated()).isOne());
      JobDefinitionView current = firstCache.findEnabledJobDefinition(TENANT, code);
      assertThat(current.jobName()).isEqualTo("新配置");
      importFinished.countDown();

      JobDefinitionView snapshot = inFlight.get(30, TimeUnit.SECONDS);
      assertThat(snapshot).isNotNull();
      assertThat(snapshot.jobName()).isEqualTo("旧快照");
      JobDefinitionView subsequent = firstCache.findEnabledJobDefinition(TENANT, code);
      assertThat(subsequent.jobName()).isEqualTo("新配置");
    } finally {
      importFinished.countDown();
      RequestContextHolder.resetRequestAttributes();
      executor.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("配置导入后，事务内 MyBatis 旧正值或旧缺失不得污染事务外缓存")
  void shouldNotRefillMybatisSessionSnapshotAfterConfigImport(boolean initiallyPresent)
      throws Exception {
    String insertCalendar = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, enabled)
        values (?, 'default_calendar', 'Default calendar', 'Asia/Shanghai', true)
        on conflict (tenant_id, calendar_code) do nothing
        """;
    jdbc.update(insertCalendar, TENANT);
    String code = "SESSION_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    if (initiallyPresent) {
      TenantConfigBatchInitRequest seed = new TenantConfigBatchInitRequest();
      seed.setTargetTenantIds(List.of(TENANT));
      seed.setStrict(true);
      seed.setJobDefinitions(List.of(job(code, "会话旧值")));
      assertThat(initService.batchInit(seed, "integration-test", "seed-" + code).failureTenants())
          .isZero();
    }
    awaitAppliedRevision();
    String wildcardKey =
        BatchRedisKeys.configInvalidationKeyRevision(TENANT, "job-definition", "*");
    String previousWildcard = redis.opsForValue().get(wildcardKey);
    long beforeImport = previousWildcard == null ? 0 : Long.parseLong(previousWildcard);
    CountDownLatch firstReadFinished = new CountDownLatch(1);
    CountDownLatch importFinished = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    try {
      Future<JobDefinitionView> inFlight = executor.submit(() -> {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setReadOnly(true);
        return outer.execute(outerStatus -> {
          Object outerResource = TransactionSynchronizationManager.getResource(platformDataSource);
          assertThat(outerResource).isNotNull();
          TransactionTemplate inner = new TransactionTemplate(transactionManager);
          inner.setReadOnly(true);
          inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
          JobDefinitionView selected = inner.execute(innerStatus -> {
            Object innerResource =
                TransactionSynchronizationManager.getResource(platformDataSource);
            assertThat(innerResource).isNotSameAs(outerResource);
            JobDefinitionView first = firstCache.findEnabledJobDefinition(TENANT, code);
            if (initiallyPresent) {
              assertThat(first.jobName()).isEqualTo("会话旧值");
            } else {
              assertThat(first).isNull();
            }
            firstReadFinished.countDown();
            try {
              if (!importFinished.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("配置导入未完成");
              }
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException("等待配置导入时中断", exception);
            }
            return firstCache.findEnabledJobDefinition(TENANT, code);
          });
          assertThat(TransactionSynchronizationManager.getResource(platformDataSource))
              .isSameAs(outerResource);
          return selected;
        });
      });
      assertThat(firstReadFinished.await(30, TimeUnit.SECONDS)).isTrue();

      ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
      bundle.setJobDefinitions(List.of(job(code, "会话新值")));
      ConfigSyncImportRequest request = new ConfigSyncImportRequest();
      request.setTenantId(TENANT);
      request.setSourceEnv("session-" + code);
      request.setTargetEnv("integration-test");
      request.setTargetTenantIds(List.of(TENANT));
      request.setMode(InitMode.UPSERT);
      request.setBundle(bundle);
      RequestContextHolder.setRequestAttributes(
          new ServletRequestAttributes(new MockHttpServletRequest()));
      ConfigSyncImportResponse response = syncService.importBundle(request);
      assertThat(response.result().results()).singleElement().satisfies(result -> {
        assertThat(result.jobDefinitions().updated()).isEqualTo(initiallyPresent ? 1 : 0);
        assertThat(result.jobDefinitions().created()).isEqualTo(initiallyPresent ? 0 : 1);
      });
      String currentWildcard = redis.opsForValue().get(wildcardKey);
      assertThat(currentWildcard).isNotNull();
      assertThat(Long.parseLong(currentWildcard)).isGreaterThan(beforeImport);
      awaitAppliedRevision();
      importFinished.countDown();
      JobDefinitionView transactionResult = inFlight.get(30, TimeUnit.SECONDS);
      if (initiallyPresent) {
        assertThat(transactionResult).isNotNull();
        assertThat(transactionResult.jobName()).isEqualTo("会话旧值");
      } else {
        assertThat(transactionResult).isNull();
      }
      JobDefinitionView subsequent = firstCache.findEnabledJobDefinition(TENANT, code);
      assertThat(subsequent).isNotNull();
      assertThat(subsequent.jobName()).isEqualTo("会话新值");
    } finally {
      importFinished.countDown();
      RequestContextHolder.resetRequestAttributes();
      executor.shutdownNow();
    }
  }

  private OrchestratorChild startOrchestratorChild(String suffix) throws Exception {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    Map<String, Object> properties = new LinkedHashMap<>();
    AbstractIntegrationTest.registerDynamicProperties(
        (name, supplier) -> properties.put(name, supplier.get()));
    properties.put("spring.profiles.active", "test,e2e");
    properties.put("spring.flyway.enabled", false);
    properties.put("spring.main.web-application-type", "servlet");
    properties.put("server.port", port);
    properties.put("local.server.port", port);
    properties.put("spring.quartz.auto-startup", false);
    properties.put("batch.startup-self-check.enabled", false);
    // 拉长测试窗口以区分事件失效与自然过期/周期对账；生产默认值分别为 250ms、10s。
    properties.put("batch.orchestrator.config-cache.local-positive-ttl-millis", 120000);
    properties.put("batch.orchestrator.config-cache.reconcile-interval-millis", 3600000);
    properties.put("batch.trigger.consumer.group-id", "config-cache-process-" + suffix);
    properties.put("management.endpoints.web.exposure.include", "health,info,metrics");
    List<String> command = new ArrayList<>(List.of(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp",
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
        E2eReportRecoveryOrchestratorApplication.class.getName()));
    properties.forEach((name, value) -> command.add("--" + name + "=" + value));
    Path log = Files.createTempFile("config-cache-orchestrator-" + suffix + "-", ".log");
    Process process = new ProcessBuilder(command)
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .start();
    OrchestratorChild child = new OrchestratorChild(process, port, log);
    try {
      await().atMost(Duration.ofSeconds(120)).until(() ->
          Files.readString(log).contains("Started E2eReportRecoveryOrchestratorApplication")
              || !process.isAlive());
      assertThat(process.isAlive()).as("调度子进程启动日志：%s", log).isTrue();
      return child;
    } catch (Exception | AssertionError failure) {
      child.close();
      throw failure;
    }
  }

  private record OrchestratorChild(Process process, int port, Path log) implements AutoCloseable {
    private JsonNode request(String path, String body) throws Exception {
      HttpRequest.Builder builder = HttpRequest.newBuilder()
          .uri(URI.create("http://127.0.0.1:" + port + path))
          .timeout(Duration.ofSeconds(10));
      if (body == null) {
        builder.GET();
      } else {
        builder.header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
      }
      HttpResponse<String> response =
          HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).as("进程 %s 的响应，日志：%s", process.pid(), log)
          .isEqualTo(200);
      return JsonUtils.fromJsonStrict(response.body(), JsonNode.class);
    }

    int priority(String tenantId, String jobCode) throws Exception {
      String body = JsonUtils.toJson(Map.of(
          "tenantId", tenantId,
          "jobCode", jobCode,
          "bizDate", "2026-11-02",
          "level", "SCHEDULE_PLAN",
          "params", Map.of()));
      JsonNode data = request("/internal/orchestrator/dry-run/plan", body).path("data");
      assertThat(data.path("success").asBoolean())
          .as("调度计划响应：%s，进程日志：%s", data, log)
          .isTrue();
      JsonNode priority = data.path("summary").path("priority");
      assertThat(priority.isInt()).as("调度计划优先级：%s", data).isTrue();
      return priority.asInt();
    }

    double metric(String name, String query) throws Exception {
      JsonNode result = request("/actuator/metrics/" + name + query, null);
      JsonNode value = result.path("measurements").path(0).path("value");
      assertThat(value.isNumber()).as("进程指标：%s，日志：%s", result, log).isTrue();
      return value.asDouble();
    }

    @Override
    public void close() throws Exception {
      if (process.isAlive()) {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          assertThat(process.waitFor(15, TimeUnit.SECONDS))
              .as("调度子进程未退出，日志：%s", log)
              .isTrue();
        }
      }
      assertThat(process.isAlive()).as("调度子进程仍在运行，日志：%s", log).isFalse();
    }
  }

  private void awaitAppliedRevision() {
    String revisionKey = BatchRedisKeys.configInvalidationGlobalRevision();
    String rawRevision = redis.opsForValue().get(revisionKey);
    if (rawRevision == null) {
      return;
    }
    long revision = Long.parseLong(rawRevision);
    MeterRegistry registry = context.getBean(MeterRegistry.class);
    var gauge =
        registry.find("batch.orchestrator.config.invalidation.applied_revision").gauge();
    assertThat(gauge).isNotNull();
    await().atMost(Duration.ofSeconds(15)).until(() -> gauge.value() >= revision);
  }

  private static JobDefinitionSpec job(String code, String name) {
    JobDefinitionSpec spec = new JobDefinitionSpec();
    spec.setJobCode(code);
    spec.setJobName(name);
    spec.setJobType("IMPORT");
    spec.setScheduleType("MANUAL");
    spec.setStartGraceSeconds(0);
    spec.setEnabled(true);
    return spec;
  }
}
