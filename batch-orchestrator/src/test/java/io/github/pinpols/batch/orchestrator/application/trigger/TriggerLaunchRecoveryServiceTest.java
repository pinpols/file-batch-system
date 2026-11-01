package io.github.pinpols.batch.orchestrator.application.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure.Kind;
import io.github.pinpols.batch.orchestrator.application.service.task.LaunchApplicationService;
import io.github.pinpols.batch.orchestrator.domain.view.TriggerLaunchRecoveryView;
import io.github.pinpols.batch.orchestrator.infrastructure.failure.SpringTechnicalFailureClassificationAdapter;
import io.github.pinpols.batch.orchestrator.mapper.TriggerLaunchRecoveryMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@DisplayName("启动恢复锚点: 耗尽登记、幂等重投、业务拒绝与重试上限")
class TriggerLaunchRecoveryServiceTest {

  private TriggerLaunchRecoveryMapper mapper;
  private SecretPayloadProtector payloadProtector;
  private LaunchApplicationService launchApplicationService;
  private TriggerLaunchRecoveryService service;

  @BeforeEach
  void setUp() {
    mapper = mock(TriggerLaunchRecoveryMapper.class);
    payloadProtector = mock(SecretPayloadProtector.class);
    launchApplicationService = mock(LaunchApplicationService.class);
    TriggerLaunchRecoveryProperties properties = new TriggerLaunchRecoveryProperties();
    properties.setMaxAttempts(3);
    service = new TriggerLaunchRecoveryService(
        mapper,
        payloadProtector,
        launchApplicationService,
        properties,
        new SimpleMeterRegistry(),
        new SpringTechnicalFailureClassificationAdapter());
  }

  @Test
  @DisplayName("耗尽登记: 解析 envelope 并写密文锚点")
  void shouldParkProtectedEnvelope_whenRetriesExhausted() {
    String envelopeJson = JsonUtils.toJson(sampleEnvelope());
    when(payloadProtector.protect(envelopeJson)).thenReturn("BATCHENC_BASE64_V1:xxx");
    when(mapper.insertPending("tenant-a", "req-1", "BATCHENC_BASE64_V1:xxx", "trace-1"))
        .thenReturn(1);

    assertThat(service.park(envelopeJson, "trace-1")).isTrue();

    verify(mapper).insertPending("tenant-a", "req-1", "BATCHENC_BASE64_V1:xxx", "trace-1");
  }

  @Test
  @DisplayName("登记未写入且没有可证明的原锚点时不能报告成功")
  void shouldNotReportParked_whenNoAnchorWasWritten() {
    String envelopeJson = JsonUtils.toJson(sampleEnvelope());
    when(payloadProtector.protect(envelopeJson)).thenReturn("cipher");
    when(mapper.insertPending("tenant-a", "req-1", "cipher", "trace-1")).thenReturn(0);

    assertThat(service.park(envelopeJson, "trace-1")).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "DONE", "REJECTED"})
  @DisplayName("相同信封重复登记保留已有锚点，终态不复活")
  void shouldAcceptIdenticalExistingAnchor(String status) {
    LaunchEnvelope envelope = sampleEnvelope();
    String json = JsonUtils.toJson(envelope);
    TriggerLaunchRecoveryView existing = row(2);
    existing.setRecoveryStatus(status);
    when(payloadProtector.protect(json)).thenReturn("new-cipher");
    when(payloadProtector.reveal("cipher")).thenReturn(json);
    when(mapper.selectByIdentityForUpdate("tenant-a", "req-1")).thenReturn(existing);

    assertThat(service.park(json, "trace-1")).isTrue();
    verify(mapper, never()).requeueDead(anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("相同请求 DEAD 可重排队并重置次数")
  void shouldRequeueIdenticalDeadAnchor() {
    String json = JsonUtils.toJson(sampleEnvelope());
    TriggerLaunchRecoveryView existing = row(5);
    existing.setRecoveryStatus("DEAD");
    when(payloadProtector.protect(json)).thenReturn("new-cipher");
    when(payloadProtector.reveal("cipher")).thenReturn(json);
    when(mapper.selectByIdentityForUpdate("tenant-a", "req-1")).thenReturn(existing);
    when(mapper.requeueDead("tenant-a", "req-1", "PENDING")).thenReturn(1);

    assertThat(service.park(json, "trace-1")).isTrue();
    verify(mapper).requeueDead("tenant-a", "req-1", "PENDING");
  }

  @Test
  @DisplayName("DEAD 锚点重排队更新零行时不得报告成功")
  void shouldRejectWhenDeadRequeueMisses() {
    String json = JsonUtils.toJson(sampleEnvelope());
    TriggerLaunchRecoveryView existing = row(5);
    existing.setRecoveryStatus("DEAD");
    when(payloadProtector.protect(json)).thenReturn("new-cipher");
    when(payloadProtector.reveal("cipher")).thenReturn(json);
    when(mapper.selectByIdentityForUpdate("tenant-a", "req-1")).thenReturn(existing);
    assertThat(service.park(json, "trace-1")).isFalse();
  }

  @Test
  @DisplayName("旧密文无法解密时不能跳过冲突或重排队")
  void shouldRejectWhenExistingCiphertextIsUnreadable() {
    String json = JsonUtils.toJson(sampleEnvelope());
    TriggerLaunchRecoveryView existing = row(5);
    existing.setRecoveryStatus("DEAD");
    when(payloadProtector.protect(json)).thenReturn("new-cipher");
    when(payloadProtector.reveal("cipher")).thenThrow(new IllegalArgumentException("bad cipher"));
    when(mapper.selectByIdentityForUpdate("tenant-a", "req-1")).thenReturn(existing);
    assertThat(service.park(json, "trace-1")).isFalse();
    verify(mapper, never()).requeueDead(anyString(), anyString(), anyString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "DEAD", "DONE", "REJECTED"})
  @DisplayName("不同信封不得覆盖或复活已有请求")
  void shouldRejectConflictingExistingAnchor(String status) {
    String json = JsonUtils.toJson(sampleEnvelope());
    TriggerLaunchRecoveryView existing = row(5);
    existing.setRecoveryStatus(status);
    LaunchRequest different =
        sampleEnvelope().launchRequest().toBuilder().jobCode("other-job").build();
    String differentJson = JsonUtils.toJson(
        LaunchEnvelope.of(different, "tenant-a:req-1", BatchDateTimeSupport.utcNow()));
    when(payloadProtector.protect(json)).thenReturn("new-cipher");
    when(payloadProtector.reveal("cipher")).thenReturn(differentJson);
    when(mapper.selectByIdentityForUpdate("tenant-a", "req-1")).thenReturn(existing);

    assertThat(service.park(json, "trace-1")).isFalse();
    verify(mapper, never()).requeueDead(anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("非法 envelope 不登记锚点")
  void shouldNotPark_whenEnvelopeIsNotParsable() {
    assertThat(service.park("{not-json", null)).isFalse();
    verify(mapper, never()).insertPending(anyString(), anyString(), anyString(), any());
  }

  @Test
  @DisplayName("重投成功即置 DONE")
  void shouldMarkDone_whenRecoverySucceeds() {
    when(mapper.selectDue(any(Instant.class), anyInt())).thenReturn(List.of(row(0)));
    when(payloadProtector.reveal("cipher")).thenReturn(JsonUtils.toJson(sampleEnvelope()));

    assertThat(service.recoverDue(10)).isEqualTo(1);

    verify(mapper).markDone("tenant-a", "req-1", "DONE");
    verify(mapper, never()).markAttempt(any());
  }

  @ParameterizedTest
  @CsvSource({
    "INVALID_INPUT, HTTP_400", "NOT_FOUND, HTTP_404", "CONFLICT, HTTP_409",
    "INVALID_CONFIGURATION, HTTP_422", "RATE_LIMITED, HTTP_429", "TEMPORARILY_UNAVAILABLE, HTTP_503"
  })
  @DisplayName("用例失败保留恢复重试及既有诊断代码")
  void shouldRetry_whenOperationFails(Kind kind, String diagnosticCode) {
    when(mapper.selectDue(any(Instant.class), anyInt())).thenReturn(List.of(row(0)));
    when(payloadProtector.reveal("cipher")).thenReturn(JsonUtils.toJson(sampleEnvelope()));
    when(launchApplicationService.launchFromTrustedQueue(any()))
        .thenThrow(new OperationFailure(
            kind, "Expected event can be satisfied only by its launched EVENT request"));

    assertThat(service.recoverDue(10)).isZero();

    verify(mapper, never()).markDone(anyString(), anyString(), anyString());
    verify(mapper)
        .markRetryOrDead(argThat(
            attempt -> diagnosticCode.equals(attempt.errorCode()) && attempt.now() != null));
  }

  @Test
  @DisplayName("遗留 HTTP 状态异常继续保留原诊断代码并退避重试")
  void shouldRetry_whenLegacyResponseStatusIsThrown() {
    when(mapper.selectDue(any(Instant.class), anyInt())).thenReturn(List.of(row(0)));
    when(payloadProtector.reveal("cipher")).thenReturn(JsonUtils.toJson(sampleEnvelope()));
    when(launchApplicationService.launchFromTrustedQueue(any()))
        .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "legacy"));

    assertThat(service.recoverDue(10)).isZero();

    verify(mapper, never()).markDone(anyString(), anyString(), anyString());
    verify(mapper).markRetryOrDead(argThat(attempt -> "HTTP_409".equals(attempt.errorCode())));
  }

  @Test
  @DisplayName("应用失败的原因链包含旧异常时仍按应用失败类别记录")
  void shouldKeepOperationFailureClassification_whenCauseIsLegacyStatus() {
    when(mapper.selectDue(any(Instant.class), anyInt())).thenReturn(List.of(row(0)));
    when(payloadProtector.reveal("cipher")).thenReturn(JsonUtils.toJson(sampleEnvelope()));
    when(launchApplicationService.launchFromTrustedQueue(any()))
        .thenThrow(new OperationFailure(
            Kind.TEMPORARILY_UNAVAILABLE,
            "operation",
            new ResponseStatusException(HttpStatus.CONFLICT, "cause")));

    assertThat(service.recoverDue(10)).isZero();

    verify(mapper).markRetryOrDead(argThat(attempt -> "HTTP_503".equals(attempt.errorCode())));
  }

  @Test
  @DisplayName("业务级拒绝直接终态 REJECTED(不无限重投)")
  void shouldMarkRejected_whenBusinessRuleRejects() {
    when(mapper.selectDue(any(Instant.class), anyInt())).thenReturn(List.of(row(0)));
    when(payloadProtector.reveal("cipher")).thenReturn(JsonUtils.toJson(sampleEnvelope()));
    when(launchApplicationService.launchFromTrustedQueue(any()))
        .thenThrow(BizException.of(ResultCode.NOT_FOUND, "x"));

    assertThat(service.recoverDue(10)).isZero();

    verify(mapper).markAttempt(any());
  }

  @Test
  @DisplayName("瞬时失败按退避重排; 达到上限置 DEAD")
  void shouldRetryThenDead_whenTransientFailuresContinue() {
    when(payloadProtector.reveal("cipher")).thenReturn(JsonUtils.toJson(sampleEnvelope()));
    when(launchApplicationService.launchFromTrustedQueue(any()))
        .thenThrow(new IllegalStateException("db down"));

    when(mapper.selectDue(any(Instant.class), anyInt())).thenReturn(List.of(row(0)));
    assertThat(service.recoverDue(10)).isZero();
    verify(mapper).markRetryOrDead(any());

    when(mapper.selectDue(any(Instant.class), anyInt())).thenReturn(List.of(row(2)));
    assertThat(service.recoverDue(10)).isZero();
    verify(mapper, times(2)).markRetryOrDead(any());
  }

  private static TriggerLaunchRecoveryView row(int attempts) {
    TriggerLaunchRecoveryView entity = new TriggerLaunchRecoveryView();
    entity.setTenantId("tenant-a");
    entity.setRequestId("req-1");
    entity.setEnvelopeCiphertext("cipher");
    entity.setAttempts(attempts);
    entity.setRecoveryGeneration(0L);
    entity.setRecoveryStatus("PENDING");
    return entity;
  }

  private static LaunchEnvelope sampleEnvelope() {
    LaunchRequest request = new LaunchRequest(
        "tenant-a",
        "test-job",
        LocalDate.parse("2026-10-24"),
        TriggerType.MANUAL,
        "req-1",
        "trace-1",
        Map.of());
    return LaunchEnvelope.of(request, "tenant-a:req-1", BatchDateTimeSupport.utcNow());
  }
}
