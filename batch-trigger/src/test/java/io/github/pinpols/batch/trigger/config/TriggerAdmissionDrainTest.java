package io.github.pinpols.batch.trigger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.trigger.auth.TriggerApiKeyPrincipal;
import io.github.pinpols.batch.trigger.auth.TriggerApiKeyVerifier;
import io.github.pinpols.batch.trigger.service.TriggerRequestPolicy;
import io.github.pinpols.batch.trigger.service.TriggerLaunchAdmissionService;
import io.github.pinpols.batch.trigger.service.TriggerApiApplicationService;
import io.github.pinpols.batch.trigger.infrastructure.TriggerDrainState;
import io.github.pinpols.batch.trigger.infrastructure.TriggerGracefulShutdown;
import io.github.pinpols.batch.trigger.domain.TriggerDrainService;
import io.github.pinpols.batch.trigger.service.TriggerService;
import io.github.pinpols.batch.trigger.web.TriggerApiExceptionHandler;
import io.github.pinpols.batch.trigger.web.TriggerController;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.quartz.Scheduler;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("Trigger 排队请求进入业务写入前复核排水状态")
class TriggerAdmissionDrainTest {

  @ParameterizedTest
  @CsvSource({
    "launch, true", "events, true", "scheduled-occurrences/catch-up, true",
    "launch, false", "events, false", "scheduled-occurrences/catch-up, false"
  })
  @DisplayName("排队期间开始排水则拒绝，持续正常接纳则保留原业务调用")
  void shouldRecheckDrainingBeforeExecutingQueuedLaunch(String endpoint, boolean drainWhileQueued)
      throws Exception {
    TriggerRuntimeProperties properties = new TriggerRuntimeProperties();
    properties.setApiLaunchMaxConcurrency(1);
    properties.setApiLaunchQueueCapacity(1);
    properties.setApiLaunchQueueWaitMillis(10_000);
    TriggerApiAdmissionGuard admission = new TriggerApiAdmissionGuard(properties);
    TriggerDrainState drainState = new TriggerDrainState();
    TriggerGracefulShutdown shutdown =
        new TriggerGracefulShutdown(mock(Scheduler.class), drainState);
    TriggerService service = mock(TriggerService.class);
    TriggerApiKeyVerifier verifier = mock(TriggerApiKeyVerifier.class);
    TriggerApiKeyPrincipal principal =
        new TriggerApiKeyPrincipal(7L, "t1", "billing", "trigger.event", null);
    if ("events".equals(endpoint)) {
      when(verifier.authorizesExternalEvent(principal, "t1", "billing")).thenReturn(true);
    }
    MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new TriggerController(new TriggerApiApplicationService(
                service,
                new TriggerDrainService(shutdown),
                new TriggerLaunchAdmissionService(admission, new TriggerDrainService(shutdown)),
                new TriggerRequestPolicy(verifier))))
        .setControllerAdvice(TriggerApiExceptionHandler.forStandaloneTest())
        .setMessageConverters(new JacksonJsonHttpMessageConverter())
        .build();
    CountDownLatch holderEntered = new CountDownLatch(1);
    CountDownLatch releaseHolder = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> holder = executor.submit(() -> admission.execute(() -> {
        holderEntered.countDown();
        try {
          assertThat(releaseHolder.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new AssertionError("准入占位线程被中断", exception);
        }
        return null;
      }));
      try {
        assertThat(holderEntered.await(5, TimeUnit.SECONDS)).isTrue();
        Future<MvcResult> queued = executor.submit(() -> {
          try {
            if ("events".equals(endpoint)) {
              SecurityContextHolder.getContext()
                  .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                      principal, null, List.of()));
            }
            return mvc.perform(post("/api/triggers/" + endpoint)
                    .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "queued-drain-request")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody(endpoint)))
                .andReturn();
          } finally {
            SecurityContextHolder.clearContext();
          }
        });
        await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> assertThat(admission.queuedForTest()).isEqualTo(1));
        if (drainWhileQueued) {
          shutdown.startDraining("test-queued-request");
        }
        releaseHolder.countDown();

        MvcResult result = queued.get(5, TimeUnit.SECONDS);
        if (drainWhileQueued) {
          assertThat(result.getResponse().getStatus()).isEqualTo(409);
          assertThat(result.getResponse().getContentAsString()).contains("STATE_CONFLICT");
          verifyNoInteractions(service);
        } else {
          assertThat(result.getResponse().getStatus()).isEqualTo(200);
          switch (endpoint) {
            case "events" -> verify(service).launchExternalEvent(any());
            case "scheduled-occurrences/catch-up" ->
              verify(service).catchUpRejectedOccurrence(any());
            default -> verify(service).launch(any());
          }
        }
        holder.get(5, TimeUnit.SECONDS);
      } finally {
        releaseHolder.countDown();
      }
    }
  }

  private static String requestBody(String endpoint) {
    return switch (endpoint) {
      case "events" -> """
          {"tenantId":"t1","externalEventCode":"PAYMENT_SETTLED","externalEventId":"evt-1","bizDate":"2026-10-26"}
          """;
      case "scheduled-occurrences/catch-up" -> """
          {"tenantId":"t1","workflowDefinitionId":41,"scheduledFireTime":"2026-10-26T00:00:00Z","bizDate":"2026-10-26","requestedBy":"operator","reason":"人工核对补跑"}
          """;
      default -> """
          {"tenantId":"t1","workflowDefinitionId":41,"triggerType":"API","bizDate":"2026-10-26"}
          """;
    };
  }
}
