package io.github.pinpols.batch.console.support.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.application.idempotency.ConsoleDurableIdempotencyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.method.HandlerMethod;

@DisplayName("控制台幂等拦截器: 键校验,待完成占位与完成记录持久化")
class ConsoleIdempotencyInterceptorTest {

  private ConsoleIdempotencyStore store;
  private TaskScheduler scheduler;
  private ConsoleIdempotencyInterceptor interceptor;
  private HandlerMethod idempotentHandler;
  private ConsoleDurableIdempotencyStore durableStore;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws NoSuchMethodException {
    store = mock(ConsoleIdempotencyStore.class);
    scheduler = mock(TaskScheduler.class);
    durableStore = mock(ConsoleDurableIdempotencyStore.class);
    interceptor = new ConsoleIdempotencyInterceptor(
        store, durableStore, new BatchSecurityProperties(), scheduler);
    idempotentHandler = new HandlerMethod(
        new SampleController(), SampleController.class.getDeclaredMethod("mutate"));
  }

  @Test
  @DisplayName("预留幂等位失败时,日志不落外部键与异常原文并返回 503")
  void shouldNotLogKeyOrMessage_whenReservationFails() throws Exception {
    var request = new MockHttpServletRequest("POST", "/api/console/probe");
    request.addHeader("X-Tenant-Id", "tenant-a");
    request.addHeader(
        CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "external-key\r\nforged-entry");
    var response = new MockHttpServletResponse();
    when(store.setIfAbsent(anyString(), anyString(), any(Duration.class)))
        .thenThrow(
            new DataAccessResourceFailureException("private-storage-details\r\nforged-entry"));
    Logger logger = (Logger) LoggerFactory.getLogger(ConsoleIdempotencyInterceptor.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertThat(interceptor.preHandle(request, response, idempotentHandler)).isFalse();
      assertThat(response.getStatus()).isEqualTo(503);
      assertThat(appender.list).hasSize(1);
      var event = appender.list.getFirst();
      assertThat(event.getFormattedMessage())
          .contains("owner=PENDING:", "DataAccessResourceFailureException")
          .doesNotContain("external-key", "private-storage-details", "forged-entry", "\r", "\n");
      assertThat(event.getThrowableProxy()).isNull();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("续期任务注册到受管调度器,并在销毁时被取消")
  void shouldScheduleAndCancelRenewal_whenLifecycleRuns() {
    ScheduledFuture<?> renewal = mock(ScheduledFuture.class);
    doReturn(renewal)
        .when(scheduler)
        .scheduleWithFixedDelay(
            any(Runnable.class), any(Instant.class), eq(Duration.ofSeconds(10)));

    interceptor.startRenewal();
    interceptor.stopRenewal();

    verify(scheduler)
        .scheduleWithFixedDelay(
            any(Runnable.class), any(Instant.class), eq(Duration.ofSeconds(10)));
    verify(renewal).cancel(false);
  }

  @Test
  @DisplayName("幂等端点的更新请求缺少幂等键时,返回 400 并提示缺少幂等键")
  void shouldRejectPut_whenIdempotencyKeyIsMissing() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/console/jobs/demo");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean allowed = interceptor.preHandle(request, response, idempotentHandler);

    assertThat(allowed).isFalse();
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("MISSING_IDEMPOTENCY_KEY");
    verifyNoInteractions(store);
  }

  @Test
  @DisplayName("删除请求携带幂等键时,预留待完成占位并记录键与归属")
  void shouldReservePendingSlot_whenDeleteCarriesIdempotencyKey() throws Exception {
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
  @DisplayName("缓存记录已过期但持久化记录显示完成时,请求返回 409")
  void shouldRejectRequest_whenDurableRecordMarksCompletion() throws Exception {
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
  @DisplayName("缓存写入完成标记失败时,成功请求仍写入持久化完成记录")
  void shouldPersistDurableCompletion_whenCacheCompletionFails() throws Exception {
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
  @DisplayName("幂等键已完成时,重复请求返回 409 并提示重复提交")
  void shouldReturnConflict_whenIdempotencyKeyAlreadyCompleted() throws Exception {
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
