package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncImportRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.ResourceQueueSpec;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleJwtService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.console.security.single-session-enabled=false",
      "batch.console.ai.enabled=false",
      "batch.console.security.rate-limit.expensive-op-user-limit-per-minute=100"
    })
@Import(ConfigSyncAuditRecoveryIntegrationTest.SecurityMode.class)
@DisplayName("配置同步收据恢复: PostgreSQL 提交边界、认证恢复、重复请求与在途对账")
class ConfigSyncAuditRecoveryIntegrationTest extends AbstractIntegrationTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMode {
    @Bean
    static BeanPostProcessor requireAuthentication() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
          if (bean instanceof BatchSecurityProperties security) {
            security.setBypassMode(false);
          }
          return bean;
        }
      };
    }
  }

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ConsoleJwtService jwtService;
  @Autowired private ObjectMapper objectMapper;
  @MockitoSpyBean private ConfigSyncImportExecutor importExecutor;
  private final List<Fixture> ownedFixtures = new ArrayList<>();

  @AfterEach
  void removeOwnedFixtures() {
    String deleteReceiptsSql = "delete from batch.config_sync_target_receipt where audit_tenant_id = ?";
    String deleteAuditSql = "delete from batch.config_sync_log where tenant_id = ?";
    String deleteQueuesSql = "delete from batch.resource_queue where tenant_id = ?";
    String deleteTenantSql = "delete from batch.tenant where tenant_id = ?";
    for (Fixture fixture : ownedFixtures) {
      jdbc.update(deleteReceiptsSql, fixture.auditTenant());
      jdbc.update(deleteAuditSql, fixture.auditTenant());
      for (String tenant : List.of(
          fixture.auditTenant(), fixture.targets().getFirst(), fixture.targets().getLast())) {
        jdbc.update(deleteQueuesSql, tenant);
        jdbc.update(deleteTenantSql, tenant);
      }
    }
    ownedFixtures.clear();
  }

  @Test
  @DisplayName("持续审计故障修复后经认证接口补录原结果, 同键重提不执行目标且非管理员不能恢复")
  void shouldReconcileCommittedTargets_whenAuditStorageRecovers() throws Exception {
    Fixture fixture = fixture();
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");
    String key = "import-" + fixture.auditTenant();
    String fault = installFault(fixture, false);
    Long logId;
    try {
      post(client, admin, "/api/console/config/sync/import", key, fixture.request())
          .expectStatus().is5xxServerError();
      logId = logId(fixture);
      assertPersisted(fixture, 2, 2, "RUNNING");
      post(client, admin, "/api/console/config/sync/import", key, fixture.request())
          .expectStatus().isEqualTo(409);
      assertPersisted(fixture, 2, 2, "RUNNING");
      String uri = reconcileUri(fixture.auditTenant(), logId);
      post(client, admin, uri, "still-fault-" + key, Map.of())
          .expectStatus().is5xxServerError();
      assertPersisted(fixture, 2, 2, "RUNNING");
    } finally {
      removeFault(fault, false);
    }

    String uri = reconcileUri(fixture.auditTenant(), logId);
    Auth reader = authenticate(client, fixture.auditTenant(), "ROLE_TENANT_ADMIN");
    post(client, reader, uri, "denied-" + key, Map.of()).expectStatus().isForbidden();
    String wrongTenantUri = reconcileUri(fixture.targets().getFirst(), logId);
    post(client, admin, wrongTenantUri, "wrong-tenant-" + key, Map.of())
        .expectStatus().isNotFound();
    String body = post(client, admin, uri, "recover-" + key, Map.of())
        .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    JsonNode data = objectMapper.readTree(body).path("data");
    assertThat(data.path("syncStatus").asText()).isEqualTo("SUCCESS");
    assertThat(data.path("successItems").asInt()).isEqualTo(2);
    assertThat(data.path("skippedItems").asInt()).isZero();
    assertPersisted(fixture, 2, 2, "SUCCESS");
    String auditSql = """
        select detail_json->>'reconciledBy' from batch.config_sync_log where id = ?
        """;
    String reconciledBy = jdbc.queryForObject(auditSql, String.class, logId);
    assertThat(reconciledBy).isEqualTo(admin.username());
    post(client, admin, uri, "repeat-recover-" + key, Map.of()).expectStatus().isOk();
    assertPersisted(fixture, 2, 2, "SUCCESS");
  }

  @Test
  @DisplayName("目标已提交但协调调用中断后并发对账返回同一终态, 不重复执行目标")
  void shouldReconcileConcurrently_whenCoordinatorStopsAfterCommit() throws Exception {
    Fixture fixture = fixture();
    doAnswer(invocation -> {
          invocation.callRealMethod();
          throw new SimulatedCoordinatorStop();
        }).when(importExecutor).execute(any(), any());
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");
    post(client, admin, "/api/console/config/sync/import", "stop-" + fixture.auditTenant(),
        fixture.request()).expectStatus().is5xxServerError();
    assertPersisted(fixture, 2, 2, "RUNNING");
    Long logId = logId(fixture);
    String uri = reconcileUri(fixture.auditTenant(), logId);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> reconcileAfterRelease(
          client, admin, uri, "recover-first-" + fixture.auditTenant(), ready, release));
      var second = executor.submit(() -> reconcileAfterRelease(
          client, admin, uri, "recover-second-" + fixture.auditTenant(), ready, release));
      try {
        boolean bothReady = ready.await(20, TimeUnit.SECONDS);
        assertThat(bothReady).isTrue();
      } finally {
        release.countDown();
      }
      String firstBody = first.get(30, TimeUnit.SECONDS);
      String secondBody = second.get(30, TimeUnit.SECONDS);
      JsonNode firstData = objectMapper.readTree(firstBody).path("data");
      JsonNode secondData = objectMapper.readTree(secondBody).path("data");
      assertThat(firstData).isEqualTo(secondData);
      assertThat(firstData.path("syncStatus").asText()).isEqualTo("SUCCESS");
      assertThat(firstData.path("successItems").asInt()).isEqualTo(2);
      assertThat(firstData.path("skippedItems").asInt()).isZero();
    }
    assertPersisted(fixture, 2, 2, "SUCCESS");
  }

  @Test
  @DisplayName("导入仍执行时对账保持未确认, 放行后原请求正常完成且没有重复配置")
  void shouldKeepUnknown_whenOriginalImportIsStillActive() throws Exception {
    Fixture fixture = fixture();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(invocation -> {
          entered.countDown();
          if (!release.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("test release barrier timed out");
          }
          return invocation.callRealMethod();
        }).when(importExecutor).execute(any(), any());
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");
    String key = "active-" + fixture.auditTenant();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var pending = executor.submit(() -> post(
          client, admin, "/api/console/config/sync/import", key, fixture.request())
          .expectStatus().isOk());
      try {
        boolean started = entered.await(20, TimeUnit.SECONDS);
        assertThat(started).isTrue();
        Long logId = logId(fixture);
        String uri = reconcileUri(fixture.auditTenant(), logId);
        post(client, admin, uri, "inspect-" + key, Map.of())
            .expectStatus().isOk().expectBody().jsonPath("$.data.syncStatus").isEqualTo("RUNNING");
        assertPersisted(fixture, 0, 0, "RUNNING");
      } finally {
        release.countDown();
      }
      pending.get(30, TimeUnit.SECONDS);
    }
    assertPersisted(fixture, 2, 2, "SUCCESS");
  }

  @Test
  @DisplayName("目标收据写入失败时配置与收据一起回滚, 对账不伪造结果")
  void shouldRollbackTargetChanges_whenReceiptCannotCommit() {
    Fixture fixture = fixture();
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");
    String fault = installFault(fixture, true);
    try {
      post(client, admin, "/api/console/config/sync/import", "receipt-fail-" + fixture.auditTenant(),
          fixture.request()).expectStatus().is5xxServerError();
      assertPersisted(fixture, 0, 0, "RUNNING");
    } finally {
      removeFault(fault, true);
    }
    Long logId = logId(fixture);
    String uri = reconcileUri(fixture.auditTenant(), logId);
    post(client, admin, uri, "receipt-check-" + fixture.auditTenant(), Map.of())
        .expectStatus().isOk().expectBody().jsonPath("$.data.syncStatus").isEqualTo("RUNNING");
    assertPersisted(fixture, 0, 0, "RUNNING");
  }

  private Fixture fixture() {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String audit = "sync-audit-" + suffix;
    List<String> targets = List.of("sync-a-" + suffix, "sync-b-" + suffix);
    String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
        """;
    ResourceQueueSpec queue = new ResourceQueueSpec();
    queue.setQueueCode("sync_receipt_queue");
    queue.setQueueName("sync receipt queue");
    queue.setQueueType("MIXED");
    ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
    bundle.setResourceQueues(List.of(queue));
    ConfigSyncImportRequest request = new ConfigSyncImportRequest();
    request.setTenantId(audit);
    request.setSourceEnv("test-source");
    request.setTargetEnv("test-target");
    request.setTargetTenantIds(targets);
    request.setBundle(bundle);
    Fixture fixture = new Fixture(audit, targets, request);
    ownedFixtures.add(fixture);
    for (String tenant : List.of(audit, targets.getFirst(), targets.getLast())) {
      jdbc.update(insertTenantSql, tenant, tenant);
    }
    return fixture;
  }

  private String reconcileAfterRelease(
      WebTestClient client, Auth auth, String uri, String key,
      CountDownLatch ready, CountDownLatch release) throws InterruptedException {
    ready.countDown();
    boolean released = release.await(30, TimeUnit.SECONDS);
    assertThat(released).isTrue();
    return post(client, auth, uri, key, Map.of()).expectStatus().isOk()
        .expectBody(String.class).returnResult().getResponseBody();
  }

  private String installFault(Fixture fixture, boolean receipt) {
    String fault = "sync_fault_" + UUID.randomUUID().toString().replace("-", "");
    String createFunctionSql = """
        create function batch.%s() returns trigger language plpgsql as $$
        begin raise exception 'test config sync persistence failure' using errcode = '23514'; end;
        $$
        """.formatted(fault);
    String table = receipt ? "config_sync_target_receipt" : "config_sync_log";
    String event = receipt ? "insert" : "update";
    String tenantColumn = receipt ? "audit_tenant_id" : "tenant_id";
    String createTriggerSql = """
        create trigger %s before %s on batch.%s for each row
        when (NEW.%s = '%s') execute function batch.%s()
        """.formatted(fault, event, table, tenantColumn, fixture.auditTenant(), fault);
    jdbc.execute(createFunctionSql);
    try {
      jdbc.execute(createTriggerSql);
      return fault;
    } catch (RuntimeException failure) {
      String dropFunctionSql = "drop function if exists batch.%s()".formatted(fault);
      try {
        jdbc.execute(dropFunctionSql);
      } catch (RuntimeException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  private void removeFault(String fault, boolean receipt) {
    String table = receipt ? "config_sync_target_receipt" : "config_sync_log";
    String dropTriggerSql = "drop trigger if exists %s on batch.%s".formatted(fault, table);
    String dropFunctionSql = "drop function if exists batch.%s()".formatted(fault);
    jdbc.execute(dropTriggerSql);
    jdbc.execute(dropFunctionSql);
  }

  private Long logId(Fixture fixture) {
    String selectLogSql = "select id from batch.config_sync_log where tenant_id = ?";
    return jdbc.queryForObject(selectLogSql, Long.class, fixture.auditTenant());
  }

  private void assertPersisted(Fixture fixture, int configs, int receipts, String status) {
    String selectConfigurationsSql = """
        select count(*) from batch.resource_queue where tenant_id in (?, ?)
        """;
    String selectReceiptsSql = """
        select count(*) from batch.config_sync_target_receipt where audit_tenant_id = ?
        """;
    String selectAuditSql = """
        select sync_status from batch.config_sync_log where tenant_id = ?
        """;
    Integer actualConfigs = jdbc.queryForObject(selectConfigurationsSql, Integer.class,
        fixture.targets().getFirst(), fixture.targets().getLast());
    Integer actualReceipts = jdbc.queryForObject(selectReceiptsSql, Integer.class, fixture.auditTenant());
    String actualStatus = jdbc.queryForObject(selectAuditSql, String.class, fixture.auditTenant());
    assertThat(actualConfigs).isEqualTo(configs);
    assertThat(actualReceipts).isEqualTo(receipts);
    assertThat(actualStatus).isEqualTo(status);
  }

  private WebTestClient client() {
    return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(60)).build();
  }

  private Auth authenticate(WebTestClient client, String tenant, String role) {
    String username = "sync-operator-" + role;
    String jwt = jwtService.issueToken(username, tenant, Set.of(role)).accessToken();
    ResponseCookie csrfCookie = client.get().uri("/api/console/auth/check")
        .cookie("batch_console_token", jwt).exchange().expectStatus().isNoContent()
        .returnResult(Void.class).getResponseCookies().getFirst("XSRF-TOKEN");
    assertThat(csrfCookie).isNotNull();
    return new Auth(username, tenant, jwt, csrfCookie.getValue());
  }

  private WebTestClient.ResponseSpec post(
      WebTestClient client, Auth auth, String uri, String key, Object body) {
    return client.post().uri(uri).cookie("batch_console_token", auth.jwt())
        .cookie("XSRF-TOKEN", auth.csrf()).header("X-XSRF-TOKEN", auth.csrf())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, auth.tenant())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, key)
        .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
  }

  private static String reconcileUri(String tenant, Long logId) {
    return "/api/console/config/sync/logs/" + logId + "/reconcile?tenantId=" + tenant;
  }

  private record Fixture(String auditTenant, List<String> targets, ConfigSyncImportRequest request) {}
  private record Auth(String username, String tenant, String jwt, String csrf) {}
  private static final class SimulatedCoordinatorStop extends Error {}
}
