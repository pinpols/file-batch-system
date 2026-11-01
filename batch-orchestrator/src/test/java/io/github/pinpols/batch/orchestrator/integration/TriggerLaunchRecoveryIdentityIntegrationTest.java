package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure.Kind;
import io.github.pinpols.batch.orchestrator.application.service.task.LaunchApplicationService;
import io.github.pinpols.batch.orchestrator.application.trigger.TriggerLaunchRecoveryProperties;
import io.github.pinpols.batch.orchestrator.application.trigger.TriggerLaunchRecoveryService;
import io.github.pinpols.batch.orchestrator.infrastructure.failure.SpringTechnicalFailureClassificationAdapter;
import io.github.pinpols.batch.orchestrator.mapper.TriggerLaunchRecoveryMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("启动恢复锚点请求身份和并发")
class TriggerLaunchRecoveryIdentityIntegrationTest extends AbstractIntegrationTest {
  private final String testTenant = "recovery-identity-" + System.nanoTime();

  private final TriggerLaunchRecoveryService service;

  private final SecretPayloadProtector protector;

  private final JdbcTemplate jdbc;

  private final TriggerLaunchRecoveryMapper mapper;

  @Autowired
  TriggerLaunchRecoveryIdentityIntegrationTest(
      TriggerLaunchRecoveryService service,
      SecretPayloadProtector protector,
      JdbcTemplate jdbc,
      TriggerLaunchRecoveryMapper mapper) {
    this.service = service;
    this.protector = protector;
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @AfterEach
  void cleanUp() {
    String deleteTenantRecoverySql = "delete from batch.trigger_launch_recovery where tenant_id=?";
    jdbc.update(deleteTenantRecoverySql, testTenant);
  }

  @ParameterizedTest
  @ValueSource(strings = {"retry", "rejected"})
  @DisplayName("重新入队后的次数不受上轮迟到失败影响")
  void shouldPreserveRequeuedAttempts_whenPreviousFailureArrivesLate(String outcome)
      throws Exception {
    String tenant = testTenant;
    String original = envelope(tenant, "request", "job-a");
    assertThat(service.park(original, "trace")).isTrue();
    String updateAttemptsSql =
        "update batch.trigger_launch_recovery set attempts=2 where tenant_id=? and request_id=?";
    jdbc.update(updateAttemptsSql, tenant, "request");
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    LaunchApplicationService launch = mock(LaunchApplicationService.class);
    when(launch.launchFromTrustedQueue(any())).thenAnswer(invocation -> {
      if (calls.incrementAndGet() == 1) {
        firstEntered.countDown();
        if (!releaseFirst.await(15, TimeUnit.SECONDS)) {
          throw new IllegalStateException("timed out waiting for requeue");
        }
        if ("rejected".equals(outcome)) {
          throw BizException.of(ResultCode.NOT_FOUND, "late business rejection");
        }
      }
      throw new IllegalStateException("launch failed");
    });
    TriggerLaunchRecoveryProperties properties = new TriggerLaunchRecoveryProperties();
    properties.setMaxAttempts(3);
    TriggerLaunchRecoveryService recovering = new TriggerLaunchRecoveryService(
        mapper,
        protector,
        launch,
        properties,
        new SimpleMeterRegistry(),
        new SpringTechnicalFailureClassificationAdapter());
    try {
      try (var pool = Executors.newSingleThreadExecutor()) {
        var first = pool.submit(() -> recovering.recoverDue(10));
        try {
          assertThat(firstEntered.await(15, TimeUnit.SECONDS)).isTrue();
          assertThat(recovering.recoverDue(10)).isZero();
          assertThat(service.park(original, "trace")).isTrue();
        } finally {
          releaseFirst.countDown();
        }
        assertThat(first.get(15, TimeUnit.SECONDS)).isZero();
      }
      String selectRecoverySql =
          "select recovery_status, attempts from batch.trigger_launch_recovery where tenant_id=? and request_id=?";
      Map<String, Object> row = jdbc.queryForMap(selectRecoverySql, tenant, "request");
      assertThat(row).containsEntry("attempts", 0).containsEntry("recovery_status", "PENDING");
    } finally {
      String deleteRecoverySql =
          "delete from batch.trigger_launch_recovery where tenant_id=? and request_id=?";
      jdbc.update(deleteRecoverySql, tenant, "request");
    }
  }

  @Test
  @DisplayName("重叠失败按数据库累计次数达到上限后停止重试")
  void shouldRespectAttemptLimit_whenFailuresOverlap() throws Exception {
    String tenant = testTenant;
    assertThat(service.park(envelope(tenant, "request", "job-a"), "trace")).isTrue();
    String updateAttemptsSql =
        "update batch.trigger_launch_recovery set attempts=1 where tenant_id=? and request_id=?";
    jdbc.update(updateAttemptsSql, tenant, "request");
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    LaunchApplicationService launch = mock(LaunchApplicationService.class);
    when(launch.launchFromTrustedQueue(any())).thenAnswer(invocation -> {
      if (calls.incrementAndGet() == 1) {
        firstEntered.countDown();
        if (!releaseFirst.await(15, TimeUnit.SECONDS)) {
          throw new IllegalStateException("timed out waiting for competing recovery");
        }
      }
      throw new IllegalStateException("launch failed");
    });
    TriggerLaunchRecoveryProperties properties = new TriggerLaunchRecoveryProperties();
    properties.setMaxAttempts(3);
    TriggerLaunchRecoveryService recovering = new TriggerLaunchRecoveryService(
        mapper,
        protector,
        launch,
        properties,
        new SimpleMeterRegistry(),
        new SpringTechnicalFailureClassificationAdapter());
    try {
      try (var pool = Executors.newSingleThreadExecutor()) {
        var first = pool.submit(() -> recovering.recoverDue(10));
        try {
          assertThat(firstEntered.await(15, TimeUnit.SECONDS)).isTrue();
          assertThat(recovering.recoverDue(10)).isZero();
        } finally {
          releaseFirst.countDown();
        }
        assertThat(first.get(15, TimeUnit.SECONDS)).isZero();
      }
      String selectRecoverySql =
          "select recovery_status, attempts from batch.trigger_launch_recovery where tenant_id=? and request_id=?";
      Map<String, Object> row = jdbc.queryForMap(selectRecoverySql, tenant, "request");
      assertThat(row).containsEntry("attempts", 3).containsEntry("recovery_status", "DEAD");
    } finally {
      String deleteRecoverySql =
          "delete from batch.trigger_launch_recovery where tenant_id=? and request_id=?";
      jdbc.update(deleteRecoverySql, tenant, "request");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"DEAD", "REJECTED"})
  @DisplayName("重叠恢复中实际成功不得被另一轮失败终态覆盖")
  void shouldPreserveSuccess_whenConcurrentAttemptIsExhausted(String competingOutcome)
      throws Exception {
    String tenant = testTenant;
    String original = envelope(tenant, "request", "job-a");
    assertThat(service.park(original, "trace")).isTrue();
    String setAttempts =
        "update batch.trigger_launch_recovery set attempts=2 where tenant_id=? and request_id=?";
    jdbc.update(setAttempts, tenant, "request");
    CountDownLatch successEntered = new CountDownLatch(1);
    CountDownLatch releaseSuccess = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    LaunchApplicationService launch = mock(LaunchApplicationService.class);
    when(launch.launchFromTrustedQueue(any())).thenAnswer(invocation -> {
      if (calls.incrementAndGet() == 1) {
        successEntered.countDown();
        if (!releaseSuccess.await(15, TimeUnit.SECONDS)) {
          throw new IllegalStateException("timed out waiting for competing recovery");
        }
        return null;
      }
      if ("REJECTED".equals(competingOutcome)) {
        throw BizException.of(ResultCode.NOT_FOUND, "competing business rejection");
      }
      throw new IllegalStateException("competing recovery failed");
    });
    TriggerLaunchRecoveryProperties properties = new TriggerLaunchRecoveryProperties();
    properties.setMaxAttempts(3);
    TriggerLaunchRecoveryService recovering = new TriggerLaunchRecoveryService(
        mapper,
        protector,
        launch,
        properties,
        new SimpleMeterRegistry(),
        new SpringTechnicalFailureClassificationAdapter());
    try (var pool = Executors.newSingleThreadExecutor()) {
      var successful = pool.submit(() -> recovering.recoverDue(10));
      try {
        assertThat(successEntered.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(recovering.recoverDue(10)).isZero();
        String intermediateSql =
            "select recovery_status from batch.trigger_launch_recovery where tenant_id=? and request_id=?";
        assertThat(jdbc.queryForObject(intermediateSql, String.class, tenant, "request"))
            .isEqualTo(competingOutcome);
      } finally {
        releaseSuccess.countDown();
      }
      assertThat(successful.get(15, TimeUnit.SECONDS)).isEqualTo(1);
    }
    String sql =
        "select recovery_status from batch.trigger_launch_recovery where tenant_id=? and request_id=?";
    assertThat(jdbc.queryForObject(sql, String.class, tenant, "request")).isEqualTo("DONE");
  }

  @Test
  @DisplayName("预期核对冲突保留锚点和退避，重试成功后才完成")
  void shouldPreserveOriginalEnvelope_whenRetryConflicts() {
    String tenant = testTenant;
    String original = envelope(tenant, "request", "job-a");
    LaunchApplicationService launch = mock(LaunchApplicationService.class);
    when(launch.launchFromTrustedQueue(any()))
        .thenThrow(new OperationFailure(Kind.CONFLICT, "event expectation conflict"))
        .thenReturn(null);
    TriggerLaunchRecoveryProperties properties = new TriggerLaunchRecoveryProperties();
    TriggerLaunchRecoveryService recovering = new TriggerLaunchRecoveryService(
        mapper,
        protector,
        launch,
        properties,
        new SimpleMeterRegistry(),
        new SpringTechnicalFailureClassificationAdapter());
    assertThat(service.park(original, "trace")).isTrue();

    assertThat(recovering.recoverDue(10)).isZero();
    String sql =
        "select recovery_status, attempts, last_error_code, envelope_ciphertext, next_attempt_at > now() as deferred from batch.trigger_launch_recovery where tenant_id=? and request_id=?";
    Map<String, Object> failed = jdbc.queryForMap(sql, tenant, "request");
    assertThat(failed)
        .containsEntry("recovery_status", "PENDING")
        .containsEntry("attempts", 1)
        .containsEntry("last_error_code", "HTTP_409")
        .containsEntry("deferred", true);
    assertThat(protector.reveal((String) failed.get("envelope_ciphertext"))).isEqualTo(original);

    String dueSql =
        "update batch.trigger_launch_recovery set next_attempt_at=now() - interval '1 second' where tenant_id=? and request_id=?";
    jdbc.update(dueSql, tenant, "request");
    assertThat(recovering.recoverDue(10)).isEqualTo(1);
    Map<String, Object> completed = jdbc.queryForMap(sql, tenant, "request");
    assertThat(completed).containsEntry("recovery_status", "DONE").containsEntry("attempts", 1);
    assertThat(protector.reveal((String) completed.get("envelope_ciphertext"))).isEqualTo(original);
    LaunchRequest originalRequest =
        JsonUtils.fromJson(original, LaunchEnvelope.class).launchRequest();
    verify(launch, times(2)).launchFromTrustedQueue(eq(originalRequest));
  }

  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "DEAD", "DONE", "REJECTED"})
  @DisplayName("同一信封按状态幂等处理，冲突载荷不能覆盖")
  void shouldProtectRecoveryIdentity_whenEnvelopeMatchesOrConflicts(String status) {
    String tenant = testTenant;
    String original = envelope(tenant, "request", "job-a");
    String conflict = envelope(tenant, "request", "job-b");
    assertThat(service.park(original, "trace")).isTrue();
    String querySql =
        "select envelope_ciphertext, recovery_status, attempts from batch.trigger_launch_recovery where tenant_id=? and request_id=?";
    String ciphertext =
        (String) jdbc.queryForMap(querySql, tenant, "request").get("envelope_ciphertext");
    String statusSql =
        "update batch.trigger_launch_recovery set recovery_status=?, attempts=5 where tenant_id=? and request_id=?";
    jdbc.update(statusSql, status, tenant, "request");

    assertThat(service.park(conflict, "trace")).isFalse();
    Map<String, Object> before = jdbc.queryForMap(querySql, tenant, "request");
    assertThat(before)
        .containsEntry("recovery_status", status)
        .containsEntry("attempts", 5)
        .containsEntry("envelope_ciphertext", ciphertext);
    assertThat(service.park(original, "trace")).isTrue();
    Map<String, Object> after = jdbc.queryForMap(querySql, tenant, "request");
    assertThat(after)
        .containsEntry("envelope_ciphertext", ciphertext)
        .containsEntry("recovery_status", "DEAD".equals(status) ? "PENDING" : status)
        .containsEntry("attempts", "DEAD".equals(status) ? 0 : 5);
    assertThat(protector.reveal(ciphertext)).isEqualTo(original);
  }

  @Test
  @DisplayName("相同租户请求键并发写入不同信封时只能接受一个")
  void shouldPreserveOriginalEnvelope_whenConcurrentPayloadsConflict() throws Exception {
    String tenant = testTenant;
    String first = envelope(tenant, "request", "job-a");
    String second = envelope(tenant, "request", "job-b");
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> {
        start.await();
        return service.park(first, "trace");
      });
      var b = pool.submit(() -> {
        start.await();
        return service.park(second, "trace");
      });
      start.countDown();
      boolean acceptedA = a.get(20, TimeUnit.SECONDS);
      boolean acceptedB = b.get(20, TimeUnit.SECONDS);
      assertThat(List.of(acceptedA, acceptedB)).containsExactlyInAnyOrder(true, false);
      String sql =
          "select envelope_ciphertext from batch.trigger_launch_recovery where tenant_id=? and request_id=?";
      String ciphertext = jdbc.queryForObject(sql, String.class, tenant, "request");
      assertThat(protector.reveal(ciphertext)).isEqualTo(acceptedA ? first : second);
    }
  }

  private static String envelope(String tenant, String requestId, String jobCode) {
    LaunchRequest request = new LaunchRequest(
        tenant,
        jobCode,
        LocalDate.of(2026, 10, 30),
        TriggerType.MANUAL,
        requestId,
        "trace",
        Map.of());
    return JsonUtils.toJson(LaunchEnvelope.of(
        request, tenant + ":" + requestId, Instant.parse("2026-10-30T00:00:00Z")));
  }
}
