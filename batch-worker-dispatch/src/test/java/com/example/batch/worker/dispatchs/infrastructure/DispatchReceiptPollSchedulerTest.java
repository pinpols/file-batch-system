package com.example.batch.worker.dispatchs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.dispatchs.config.DispatchReceiptPollProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.StaticApplicationContext;

/** 单元测试：{@link DispatchReceiptPollScheduler#poll()} 的互斥与守卫行为。 */
class DispatchReceiptPollSchedulerTest {

  private DispatchReceiptPollProperties properties;
  private FileDispatchRepository fileDispatchRepository;
  private PlatformFileRuntimeRepository runtimeRepository;
  private DispatchReceiptPollScheduler scheduler;

  @BeforeEach
  void setUp() {
    properties = new DispatchReceiptPollProperties();
    fileDispatchRepository = mock(FileDispatchRepository.class);
    runtimeRepository = mock(PlatformFileRuntimeRepository.class);
    scheduler =
        new DispatchReceiptPollScheduler(
            properties,
            fileDispatchRepository,
            new ObjectMapper(),
            runtimeRepository,
            new SimpleMeterRegistry(),
            new BatchSecurityProperties());
    scheduler.initializeMeters();
  }

  @Test
  void shouldSkipPollingWhenDisabled() {
    properties.setEnabled(false);

    scheduler.poll();

    verify(fileDispatchRepository, never()).listPendingReceiptPolls(anyInt(), anyLong());
  }

  @Test
  void shouldSkipPollingAfterContextClosed() {
    properties.setEnabled(true);

    scheduler.stopOnContextClosed(new ContextClosedEvent(new StaticApplicationContext()));
    scheduler.poll();

    verify(fileDispatchRepository, never()).listPendingReceiptPolls(anyInt(), anyLong());
  }

  @Test
  void shouldDoNothingWhenNoPendingRows() {
    properties.setEnabled(true);
    when(fileDispatchRepository.listPendingReceiptPolls(anyInt(), anyLong())).thenReturn(List.of());

    scheduler.poll();

    verify(fileDispatchRepository).listPendingReceiptPolls(anyInt(), anyLong());
    verify(fileDispatchRepository, never()).loadChannel(anyString(), anyString());
  }

  @Test
  void shouldSkipRowWhenFileIdIsNull() {
    properties.setEnabled(true);
    Map<String, Object> row =
        Map.of(
            "tenant_id", "t1",
            "channel_code", "CH1",
            "external_request_id", "req-001"
            // 有意不放 file_id
            );
    when(fileDispatchRepository.listPendingReceiptPolls(anyInt(), anyLong()))
        .thenReturn(List.of(row));

    scheduler.poll();

    verify(fileDispatchRepository, never()).loadChannel(anyString(), anyString());
  }

  @Test
  void shouldSkipRowWhenChannelCodeIsBlank() {
    properties.setEnabled(true);
    Map<String, Object> row = new HashMap<>();
    row.put("tenant_id", "t1");
    row.put("file_id", 100L);
    row.put("channel_code", "");
    row.put("external_request_id", "req-001");
    when(fileDispatchRepository.listPendingReceiptPolls(anyInt(), anyLong()))
        .thenReturn(List.of(row));

    scheduler.poll();

    verify(fileDispatchRepository, never()).loadChannel(anyString(), anyString());
  }

  @Test
  void shouldSkipRowWhenExternalRequestIdIsNull() {
    properties.setEnabled(true);
    Map<String, Object> row = new HashMap<>();
    row.put("tenant_id", "t1");
    row.put("file_id", 200L);
    row.put("channel_code", "CH1");
    row.put("external_request_id", null);
    when(fileDispatchRepository.listPendingReceiptPolls(anyInt(), anyLong()))
        .thenReturn(List.of(row));

    scheduler.poll();

    verify(fileDispatchRepository, never()).loadChannel(anyString(), anyString());
  }

  @Test
  void shouldSkipRowWhenChannelNotFound() {
    properties.setEnabled(true);
    Map<String, Object> row =
        Map.of(
            "tenant_id", "t1",
            "file_id", 300L,
            "channel_code", "NONEXISTENT",
            "external_request_id", "req-999");
    when(fileDispatchRepository.listPendingReceiptPolls(anyInt(), anyLong()))
        .thenReturn(List.of(row));
    when(fileDispatchRepository.loadChannel("t1", "NONEXISTENT")).thenReturn(Map.of());

    scheduler.poll();

    verify(fileDispatchRepository).loadChannel("t1", "NONEXISTENT");
    verify(fileDispatchRepository, never())
        .markAcked(anyString(), anyLong(), anyString(), anyString());
  }

  @Test
  void shouldSkipRowWhenPollUrlNotConfigured() {
    properties.setEnabled(true);
    Map<String, Object> row =
        Map.of(
            "tenant_id", "t1",
            "file_id", 400L,
            "channel_code", "CH1",
            "external_request_id", "req-123");
    when(fileDispatchRepository.listPendingReceiptPolls(anyInt(), anyLong()))
        .thenReturn(List.of(row));
    // channel 配置没有 receipt_poll_url
    when(fileDispatchRepository.loadChannel("t1", "CH1"))
        .thenReturn(
            Map.of(
                "channel_code", "CH1",
                "channel_type", "API"));

    scheduler.poll();

    verify(fileDispatchRepository, never())
        .markAcked(anyString(), anyLong(), anyString(), anyString());
  }

  @Test
  void isTransientConnectivityFailure_classifiesNetExceptions() {
    // 直/嵌套 connect-refused / 超时 / DNS 都算瞬时连通性失败 (仅 message 日志, 不打 stack)。
    assertThat(DispatchReceiptPollScheduler.isTransientConnectivityFailure(new ConnectException()))
        .isTrue();
    assertThat(
            DispatchReceiptPollScheduler.isTransientConnectivityFailure(
                new SocketTimeoutException()))
        .isTrue();
    assertThat(
            DispatchReceiptPollScheduler.isTransientConnectivityFailure(new UnknownHostException()))
        .isTrue();
    assertThat(
            DispatchReceiptPollScheduler.isTransientConnectivityFailure(
                new RuntimeException("wrap", new ConnectException("refused"))))
        .isTrue();
    // 非连通性 — 业务异常应保留 stack
    assertThat(
            DispatchReceiptPollScheduler.isTransientConnectivityFailure(
                new IllegalStateException("bad payload")))
        .isFalse();
    assertThat(DispatchReceiptPollScheduler.isTransientConnectivityFailure(new IOException("io")))
        .isFalse();
  }
}
