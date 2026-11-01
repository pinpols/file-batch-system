package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.ConfigCacheInvalidationEvent;
import io.github.pinpols.batch.common.redis.BatchRedisKeys;
import io.github.pinpols.batch.common.redis.BatchRedisNamespace;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.view.JobDefinitionView;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigInvalidationSubscriber;
import io.github.pinpols.batch.orchestrator.mapper.BatchWindowMapper;
import io.github.pinpols.batch.orchestrator.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.TenantQuotaPolicyMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    classes = OrchestratorConfigCacheServiceIntegrationTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "batch.startup-self-check.enabled=false",
      "batch.orchestrator.config-cache.local-positive-ttl-millis=120000"
    })
@DisplayName("配置变更失效与并发旧读取回填的竞态")
class OrchestratorConfigCacheRefillRaceIntegrationTest extends AbstractIntegrationTest {

  @MockitoBean
  private JobDefinitionMapper jobMapper;

  @MockitoBean
  private WorkflowDefinitionMapper workflowMapper;

  @MockitoBean
  private BusinessCalendarMapper calendarMapper;

  @MockitoBean
  private BatchWindowMapper windowMapper;

  @MockitoBean
  private TenantQuotaPolicyMapper quotaMapper;

  private final OrchestratorConfigCacheService cache;
  private final StringRedisTemplate redis;
  private final ApplicationContext context;

  @Autowired
  OrchestratorConfigCacheRefillRaceIntegrationTest(
      OrchestratorConfigCacheService cache, StringRedisTemplate redis, ApplicationContext context) {
    this.cache = cache;
    this.redis = redis;
    this.context = context;
  }

  @ParameterizedTest
  @ValueSource(strings = {"WILDCARD", "EXACT"})
  @DisplayName("旧查询在失效事件后返回时，不得将旧作业定义回填给后续请求")
  void shouldNotRefillOldDefinitionAfterInvalidation(String mode) throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String tenant = "refill-" + suffix;
    String code = "JOB_" + suffix;
    JobDefinitionView original = job(tenant, code, "旧配置");
    JobDefinitionView updated = job(tenant, code, "新配置");
    AtomicReference<JobDefinitionView> committedValue = new AtomicReference<>(original);
    AtomicBoolean firstRead = new AtomicBoolean(true);
    CountDownLatch oldReadReady = new CountDownLatch(1);
    CountDownLatch releaseOldRead = new CountDownLatch(1);
    when(jobMapper.selectFirstByTenantAndCodeAndEnabled(tenant, code, true))
        .thenAnswer(invocation -> {
          JobDefinitionView selected = committedValue.get();
          if (firstRead.compareAndSet(true, false)) {
            oldReadReady.countDown();
            if (!releaseOldRead.await(10, TimeUnit.SECONDS)) {
              throw new IllegalStateException("旧查询未获放行");
            }
          }
          return selected;
        });

    var executor = Executors.newSingleThreadExecutor();
    try {
      Future<JobDefinitionView> pending =
          executor.submit(() -> cache.findEnabledJobDefinition(tenant, code));
      assertThat(oldReadReady.await(10, TimeUnit.SECONDS)).isTrue();

      committedValue.set(updated);
      String redisKey = BatchRedisKeys.config(tenant, "job-definition", code);
      redis.delete(redisKey);
      String globalRevisionKey = BatchRedisKeys.configInvalidationGlobalRevision();
      String changedCode = "WILDCARD".equals(mode) ? "*" : code;
      String changedRevisionKey =
          BatchRedisKeys.configInvalidationKeyRevision(tenant, "job-definition", changedCode);
      Long revision = redis.opsForValue().increment(globalRevisionKey);
      Long keyRevision = redis.opsForValue().increment(changedRevisionKey);
      ConfigCacheInvalidationEvent event = new ConfigCacheInvalidationEvent(
          tenant, "job-definition", changedCode, revision, keyRevision, Instant.now());
      byte[] channel = "batch:config:invalidation".getBytes(StandardCharsets.UTF_8);
      byte[] body = JsonUtils.toJson(event).getBytes(StandardCharsets.UTF_8);
      subscriber().onMessage(new DefaultMessage(channel, body), null);
      releaseOldRead.countDown();
      JobDefinitionView inFlight = pending.get(10, TimeUnit.SECONDS);
      assertThat(inFlight.jobName()).isEqualTo("旧配置");

      JobDefinitionView subsequent = cache.findEnabledJobDefinition(tenant, code);
      assertThat(subsequent.jobName()).isEqualTo("新配置");
    } finally {
      releaseOldRead.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("修订号对账清缓存后，迟到的空查询不能重新写入负缓存")
  void shouldNotRefillNegativeCacheAfterReconciliation() throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String tenant = "negative-" + suffix;
    String code = "JOB_" + suffix;
    JobDefinitionView created = job(tenant, code, "新配置");
    AtomicReference<JobDefinitionView> committedValue = new AtomicReference<>();
    AtomicBoolean firstRead = new AtomicBoolean(true);
    CountDownLatch oldReadReady = new CountDownLatch(1);
    CountDownLatch releaseOldRead = new CountDownLatch(1);
    when(jobMapper.selectFirstByTenantAndCodeAndEnabled(tenant, code, true))
        .thenAnswer(invocation -> {
          JobDefinitionView selected = committedValue.get();
          if (firstRead.compareAndSet(true, false)) {
            oldReadReady.countDown();
            if (!releaseOldRead.await(10, TimeUnit.SECONDS)) {
              throw new IllegalStateException("旧查询未获放行");
            }
          }
          return selected;
        });

    var executor = Executors.newSingleThreadExecutor();
    try {
      Future<JobDefinitionView> pending =
          executor.submit(() -> cache.findEnabledJobDefinition(tenant, code));
      assertThat(oldReadReady.await(10, TimeUnit.SECONDS)).isTrue();
      committedValue.set(created);
      String globalRevisionKey = BatchRedisKeys.configInvalidationGlobalRevision();
      redis.opsForValue().increment(globalRevisionKey);
      subscriber().reconcileRevisionGap();
      releaseOldRead.countDown();
      assertThat(pending.get(10, TimeUnit.SECONDS)).isNull();

      JobDefinitionView subsequent = cache.findEnabledJobDefinition(tenant, code);
      assertThat(subsequent.jobName()).isEqualTo("新配置");
    } finally {
      releaseOldRead.countDown();
      executor.shutdownNow();
    }
  }

  private OrchestratorConfigInvalidationSubscriber subscriber() {
    return new OrchestratorConfigInvalidationSubscriber(
        redis, new BatchRedisNamespace(""), cache, context.getBeanProvider(MeterRegistry.class));
  }

  private static JobDefinitionView job(String tenant, String code, String name) {
    return JobDefinitionView.builder()
        .id(1L)
        .tenantId(tenant)
        .jobCode(code)
        .jobName(name)
        .jobType("IMPORT")
        .enabled(true)
        .build();
  }
}
