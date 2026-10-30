package io.github.pinpols.batch.sdk.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.sdk.client.BatchPlatformClientConfig;
import io.github.pinpols.batch.sdk.internal.PlatformHttpClient;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

@DisplayName("SDK 实际执行入口：当前身份、确认顺序和失败隔离")
class TaskDispatcherExecutionTimingTest {
  @Test
  @DisplayName("使用 CLAIM 当前身份确认执行入口后才调用安全业务处理器")
  void shouldConfirmTimingBeforeSafeHandler() throws Exception {
    PlatformHttpClient http = platform();
    SdkTaskHandler handler = handler();
    Map<String, Object> identity =
        Map.of("tenantId", "tx", "workerId", "w1", "partitionInvocationId", "inv-current");
    TaskDispatcher dispatcher = dispatcher(http, handler);
    try {
      dispatcher.processInWorkerThread(message());
      InOrder order = inOrder(http, handler);
      order.verify(http).confirmExecutionStarted(42L, identity);
      order.verify(handler).execute(any(SdkTaskContext.class));
      verify(http, never()).markNonIdempotentExecutionStarted(any(), any());
    } finally {
      dispatcher.stop();
    }
  }

  @Test
  @DisplayName("开始确认失败不调用业务，也不伪造业务失败回报")
  void shouldNotExecuteOrReportWhenTimingIsUnconfirmed() throws Exception {
    PlatformHttpClient http = platform();
    when(http.confirmExecutionStarted(eq(42L), any()))
        .thenThrow(new IOException("injected renew failure"));
    SdkTaskHandler handler = handler();
    TaskDispatcher dispatcher = dispatcher(http, handler);
    try {
      dispatcher.processInWorkerThread(message());
      verify(handler, never()).execute(any());
      verify(http, never()).report(any(), anyString(), any());
    } finally {
      dispatcher.stop();
    }
  }

  @Test
  @DisplayName("确认返回取消指令时不进入业务，保留取消结果")
  void shouldHonorCancellationBeforeHandlerEntry() throws Exception {
    PlatformHttpClient http = platform();
    when(http.confirmExecutionStarted(eq(42L), any())).thenReturn(true);
    SdkTaskHandler handler = handler();
    TaskDispatcher dispatcher = dispatcher(http, handler);
    try {
      dispatcher.processInWorkerThread(message());
      verify(handler, never()).execute(any());
      assertThat(dispatcher.inFlightTaskIds()).isEmpty();
      verify(http).report(eq(42L), anyString(), any());
    } finally {
      dispatcher.stop();
    }
  }

  private PlatformHttpClient platform() throws IOException {
    PlatformHttpClient http = mock(PlatformHttpClient.class);
    when(http.claim(eq(42L), anyString(), any()))
        .thenReturn(new PlatformHttpClient.TaskClaimResponse("inv-current", "{}", null));
    return http;
  }

  private SdkTaskHandler handler() throws Exception {
    SdkTaskHandler handler = mock(SdkTaskHandler.class);
    when(handler.isSafeToReplay(any())).thenReturn(true);
    when(handler.execute(any())).thenReturn(SdkTaskResult.ok());
    return handler;
  }

  private TaskDispatcher dispatcher(PlatformHttpClient http, SdkTaskHandler handler) {
    BatchPlatformClientConfig config = BatchPlatformClientConfig.builder()
        .baseUrl("http://localhost:0")
        .tenantId("tx")
        .workerCode("w1")
        .kafkaBootstrap("localhost:9092")
        .kafkaTopicPattern("unused")
        .kafkaGroupId("unused")
        .build();
    return new TaskDispatcher(config, Map.of("echo", handler), http);
  }

  private TaskDispatchMessage message() {
    return new TaskDispatchMessage(
        42L,
        "tx",
        "job",
        "echo",
        "task-42",
        Map.of(),
        Map.of("partitionInvocationId", "inv-stale"));
  }
}
