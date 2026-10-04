package io.github.pinpols.batch.console.support.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.application.idempotency.ConsoleDurableIdempotencyStore;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class ConsoleIdempotencyInterceptorTest {

  private ConsoleIdempotencyStore store;
  private ConsoleIdempotencyInterceptor interceptor;
  private HandlerMethod idempotentHandler;
  private ConsoleDurableIdempotencyStore durableStore;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws NoSuchMethodException {
    store = mock(ConsoleIdempotencyStore.class);
    durableStore = mock(ConsoleDurableIdempotencyStore.class);
    interceptor =
        new ConsoleIdempotencyInterceptor(store, durableStore, new BatchSecurityProperties());
    idempotentHandler = new HandlerMethod(
        new SampleController(), SampleController.class.getDeclaredMethod("mutate"));
  }

  @Test
  void putOnIdempotentEndpointRequiresIdempotencyKey() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/console/jobs/demo");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean allowed = interceptor.preHandle(request, response, idempotentHandler);

    assertThat(allowed).isFalse();
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("MISSING_IDEMPOTENCY_KEY");
    verifyNoInteractions(store);
  }

  @Test
  void deleteWithIdempotencyKeyReservesPendingSlot() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/console/files/42");
    request.addHeader("X-Tenant-Id", "tenant-a");
    request.addHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "delete-key");
    MockHttpServletResponse response = new MockHttpServletResponse();
    String redisKey = "console:idempotency:tenant-a:DELETE:/api/console/files/42:delete-key";
    when(store.get(redisKey)).thenReturn(null);
    when(store.setIfAbsent(eq(redisKey), anyString(), eq(Duration.ofSeconds(30))))
        .thenReturn(true);

    boolean allowed = interceptor.preHandle(request, response, idempotentHandler);

    assertThat(allowed).isTrue();
    assertThat(request.getAttribute("console.idempotency.redisKey")).isEqualTo(redisKey);
    verify(store).setIfAbsent(eq(redisKey), anyString(), eq(Duration.ofSeconds(30)));
    assertThat(request.getAttribute("console.idempotency.owner").toString()).startsWith("PENDING:");
  }

  @Test
  void durableCompletionRecordRejectsRequestAfterRedisEntryExpired() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/console/jobs/demo");
    request.addHeader("X-Tenant-Id", "tenant-a");
    request.addHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "post-key");
    MockHttpServletResponse response = new MockHttpServletResponse();
    when(store.get(anyString())).thenReturn(null);
    when(durableStore.isCompleted(eq("tenant-a"), anyString())).thenReturn(true);

    boolean allowed = interceptor.preHandle(request, response, idempotentHandler);

    assertThat(allowed).isFalse();
    assertThat(response.getStatus()).isEqualTo(409);
    verify(store, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
  }

  @Test
  void successfulRequestPersistsDurableCompletionWhenRedisCompletionFails() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/console/jobs/demo");
    request.addHeader("X-Tenant-Id", "tenant-a");
    request.addHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "post-key");
    MockHttpServletResponse response = new MockHttpServletResponse();
    when(store.get(anyString())).thenReturn(null);
    when(store.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    interceptor.preHandle(request, response, idempotentHandler);
    response.setStatus(200);
    doThrow(new DataAccessResourceFailureException("redis down"))
        .when(store)
        .compareAndSet(anyString(), anyString(), eq("DONE"), any(Duration.class));

    interceptor.afterCompletion(request, response, idempotentHandler, null);

    verify(durableStore).markCompleted(eq("tenant-a"), anyString());
  }

  @Test
  void patchWithDoneIdempotencyKeyReturnsConflict() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/console/jobs/demo");
    request.addHeader("X-Tenant-Id", "tenant-a");
    request.addHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "patch-key");
    MockHttpServletResponse response = new MockHttpServletResponse();
    String redisKey = "console:idempotency:tenant-a:PATCH:/api/console/jobs/demo:patch-key";
    when(store.get(redisKey)).thenReturn("DONE");

    boolean allowed = interceptor.preHandle(request, response, idempotentHandler);

    assertThat(allowed).isFalse();
    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(response.getContentAsString()).contains("duplicate request");
  }

  static class SampleController {
    @Idempotent
    void mutate() {
      // no-op: the interceptor test only needs the method annotation.
    }
  }
}
