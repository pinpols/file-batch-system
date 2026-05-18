package com.example.batch.console.support.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.constants.CommonConstants;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class ConsoleIdempotencyInterceptorTest {

  private StringRedisTemplate redisTemplate;
  private ValueOperations<String, String> valueOps;
  private ConsoleIdempotencyInterceptor interceptor;
  private HandlerMethod idempotentHandler;

  @BeforeEach
  void setUp() throws NoSuchMethodException {
    redisTemplate = mock(StringRedisTemplate.class);
    valueOps = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    interceptor =
        new ConsoleIdempotencyInterceptor(redisTemplate, new BatchSecurityProperties());
    idempotentHandler =
        new HandlerMethod(new SampleController(), SampleController.class.getDeclaredMethod("mutate"));
  }

  @Test
  void putOnIdempotentEndpointRequiresIdempotencyKey() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/console/jobs/demo");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean allowed = interceptor.preHandle(request, response, idempotentHandler);

    assertThat(allowed).isFalse();
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("MISSING_IDEMPOTENCY_KEY");
    verifyNoInteractions(valueOps);
  }

  @Test
  void deleteWithIdempotencyKeyReservesPendingSlot() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/console/files/42");
    request.addHeader("X-Tenant-Id", "tenant-a");
    request.addHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "delete-key");
    MockHttpServletResponse response = new MockHttpServletResponse();
    String redisKey = "console:idempotency:tenant-a:DELETE:/api/console/files/42:delete-key";
    when(valueOps.get(redisKey)).thenReturn(null);
    when(valueOps.setIfAbsent(redisKey, "PENDING", Duration.ofSeconds(30))).thenReturn(true);

    boolean allowed = interceptor.preHandle(request, response, idempotentHandler);

    assertThat(allowed).isTrue();
    assertThat(request.getAttribute("console.idempotency.redisKey")).isEqualTo(redisKey);
    verify(valueOps).setIfAbsent(eq(redisKey), eq("PENDING"), eq(Duration.ofSeconds(30)));
  }

  @Test
  void patchWithDoneIdempotencyKeyReturnsConflict() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/console/jobs/demo");
    request.addHeader("X-Tenant-Id", "tenant-a");
    request.addHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "patch-key");
    MockHttpServletResponse response = new MockHttpServletResponse();
    String redisKey = "console:idempotency:tenant-a:PATCH:/api/console/jobs/demo:patch-key";
    when(valueOps.get(redisKey)).thenReturn("DONE");

    boolean allowed = interceptor.preHandle(request, response, idempotentHandler);

    assertThat(allowed).isFalse();
    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(response.getContentAsString()).contains("duplicate request");
  }

  static class SampleController {
    @Idempotent
    void mutate() {}
  }
}
