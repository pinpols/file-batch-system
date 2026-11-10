package io.github.pinpols.batch.worker.dispatchs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.dispatchs.config.DispatchReceiptPollProperties;
import io.github.pinpols.batch.worker.dispatchs.domain.PendingReceiptPollRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.StaticApplicationContext;

/** 单元测试：{@link DispatchReceiptPollScheduler#poll()} 的互斥与守卫行为。 */
@DisplayName("回执轮询调度:开关与停机守卫、待轮询行缺失字段的跳过,以及瞬时连通性失败分类")
class DispatchReceiptPollSchedulerTest {

  private DispatchReceiptPollProperties properties;
  private FileDispatchRepository fileDispatchRepository;
  private PlatformFileRecordRepository fileRecords;
  private BatchSecurityProperties securityProperties;
  private DispatchReceiptPollScheduler scheduler;

  @BeforeEach
  void setUp() {
    properties = new DispatchReceiptPollProperties();
    fileDispatchRepository = mock(FileDispatchRepository.class);
    fileRecords = mock(PlatformFileRecordRepository.class);
    securityProperties = new BatchSecurityProperties();
    scheduler = new DispatchReceiptPollScheduler(
        properties,
        fileDispatchRepository,
        new ObjectMapper(),
        fileRecords,
        new SimpleMeterRegistry(),
        securityProperties);
    scheduler.initializeMeters();
  }

  @Test
  @DisplayName("轮询开关关闭时,不查询任何待轮询行")
  void shouldSkipPollingWhenDisabled() {
    properties.setEnabled(false);

    scheduler.poll();

    verify(fileDispatchRepository, never()).listPendingReceiptPolls(anyInt(), anyLong());
  }

  @Test
  @DisplayName("上下文关闭事件之后再触发轮询,不再查询任何待轮询行")
  void shouldSkipPollingAfterContextClosed() {
    properties.setEnabled(true);

    scheduler.stopOnContextClosed(new ContextClosedEvent(new StaticApplicationContext()));
    scheduler.poll();

    verify(fileDispatchRepository, never()).listPendingReceiptPolls(anyInt(), anyLong());
  }

  @Test
  @DisplayName("无待轮询行时只查询一次列表,不加载任何渠道配置")
  void shouldDoNothingWhenNoPendingRows() {
    properties.setEnabled(true);
    when(fileDispatchRepository.listPendingReceiptPolls(anyInt(), anyLong())).thenReturn(List.of());

    scheduler.poll();

    verify(fileDispatchRepository).listPendingReceiptPolls(anyInt(), anyLong());
    verify(fileDispatchRepository, never()).loadChannel(anyString(), anyString());
  }

  @Test
  @DisplayName("待轮询行缺文件号时跳过该行,不加载渠道配置")
  void shouldSkipRowWhenFileIdIsNull() {
    properties.setEnabled(true);
    // 有意不放 file_id
    PendingReceiptPollRow row = new PendingReceiptPollRow("t1", null, "CH1", "req-001");
    when(fileDispatchRepository.listPendingReceiptPolls(anyInt(), anyLong()))
        .thenReturn(List.of(row));

    scheduler.poll();

    verify(fileDispatchRepository, never()).loadChannel(anyString(), anyString());
  }

  @Test
  @DisplayName("待轮询行渠道号为空时跳过该行,不加载渠道配置")
  void shouldSkipRowWhenChannelCodeIsBlank() {
    properties.setEnabled(true);
    PendingReceiptPollRow row = new PendingReceiptPollRow("t1", 100L, "", "req-001");
    when(fileDispatchRepository.listPendingReceiptPolls(anyInt(), anyLong()))
        .thenReturn(List.of(row));

    scheduler.poll();

    verify(fileDispatchRepository, never()).loadChannel(anyString(), anyString());
  }

  @Test
  @DisplayName("待轮询行外部请求号为空时跳过该行,不加载渠道配置")
  void shouldSkipRowWhenExternalRequestIdIsNull() {
    properties.setEnabled(true);
    PendingReceiptPollRow row = new PendingReceiptPollRow("t1", 200L, "CH1", null);
    when(fileDispatchRepository.listPendingReceiptPolls(anyInt(), anyLong()))
        .thenReturn(List.of(row));

    scheduler.poll();

    verify(fileDispatchRepository, never()).loadChannel(anyString(), anyString());
  }

  @Test
  @DisplayName("渠道查不到时只查询一次渠道,不把该行标记为已确认")
  void shouldSkipRowWhenChannelNotFound() {
    properties.setEnabled(true);
    PendingReceiptPollRow row = new PendingReceiptPollRow("t1", 300L, "NONEXISTENT", "req-999");
    when(fileDispatchRepository.listPendingReceiptPolls(anyInt(), anyLong()))
        .thenReturn(List.of(row));
    when(fileDispatchRepository.loadChannel("t1", "NONEXISTENT")).thenReturn(Map.of());

    scheduler.poll();

    verify(fileDispatchRepository).loadChannel("t1", "NONEXISTENT");
    verify(fileDispatchRepository, never())
        .markAcked(anyString(), anyLong(), anyString(), anyString());
  }

  @Test
  @DisplayName("渠道未配置回执轮询地址时,不把该行标记为已确认")
  void shouldSkipRowWhenPollUrlNotConfigured() {
    properties.setEnabled(true);
    PendingReceiptPollRow row = new PendingReceiptPollRow("t1", 400L, "CH1", "req-123");
    when(fileDispatchRepository.listPendingReceiptPolls(anyInt(), anyLong()))
        .thenReturn(List.of(row));
    // channel 配置没有 receipt_poll_url
    when(fileDispatchRepository.loadChannel("t1", "CH1"))
        .thenReturn(Map.of(
            "channel_code", "CH1",
            "channel_type", "API"));

    scheduler.poll();

    verify(fileDispatchRepository, never())
        .markAcked(anyString(), anyLong(), anyString(), anyString());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("acknowledgedResponses")
  @DisplayName("三种回执确认格式均推进分发记录和文件状态")
  void shouldMarkAcknowledgedReceipt(String scenario, String responseBody) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/receipt", exchange -> {
      byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    });
    server.start();
    try {
      properties.setEnabled(true);
      securityProperties.setBypassMode(true);
      PendingReceiptPollRow row = new PendingReceiptPollRow("tenant-1", 42L, "channel-1", "req-1");
      when(fileDispatchRepository.listPendingReceiptPolls(anyInt(), anyLong()))
          .thenReturn(List.of(row));
      String pollUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/receipt";
      when(fileDispatchRepository.loadChannel("tenant-1", "channel-1"))
          .thenReturn(Map.of("receipt_poll_url", pollUrl));
      when(fileDispatchRepository.markAcked("tenant-1", 42L, "channel-1", "receipt-1"))
          .thenReturn(1);

      scheduler.poll();

      verify(fileDispatchRepository).markAcked("tenant-1", 42L, "channel-1", "receipt-1");
      verify(fileRecords).updateFileStatus(eq(42L), eq("DISPATCHED"), any());
    } finally {
      scheduler.shutdown();
      server.stop(0);
    }
  }

  private static Stream<Arguments> acknowledgedResponses() {
    return Stream.of(
        Arguments.of("acknowledged", "{\"acknowledged\":true,\"receiptCode\":\"receipt-1\"}"),
        Arguments.of("status", "{\"status\":\"ACKED\",\"receiptCode\":\"receipt-1\"}"),
        Arguments.of(
            "receipt_status", "{\"receipt_status\":\"SUCCESS\",\"receiptCode\":\"receipt-1\"}"));
  }

  @Test
  @DisplayName("连接拒绝、套接字超时、域名解析失败与包装异常判为瞬时连通性失败,业务异常与普通读写异常不判")
  void isTransientConnectivityFailure_classifiesNetExceptions() {
    // 直/嵌套 connect-refused / 超时 / DNS 都算瞬时连通性失败 (仅 message 日志, 不打 stack)。
    assertThat(DispatchReceiptPollScheduler.isTransientConnectivityFailure(new ConnectException()))
        .isTrue();
    assertThat(DispatchReceiptPollScheduler.isTransientConnectivityFailure(
            new SocketTimeoutException()))
        .isTrue();
    assertThat(
            DispatchReceiptPollScheduler.isTransientConnectivityFailure(new UnknownHostException()))
        .isTrue();
    assertThat(DispatchReceiptPollScheduler.isTransientConnectivityFailure(
            new RuntimeException("wrap", new ConnectException("refused"))))
        .isTrue();
    // 非连通性 — 业务异常应保留 stack
    assertThat(DispatchReceiptPollScheduler.isTransientConnectivityFailure(
            new IllegalStateException("bad payload")))
        .isFalse();
    assertThat(DispatchReceiptPollScheduler.isTransientConnectivityFailure(new IOException("io")))
        .isFalse();
  }
}
