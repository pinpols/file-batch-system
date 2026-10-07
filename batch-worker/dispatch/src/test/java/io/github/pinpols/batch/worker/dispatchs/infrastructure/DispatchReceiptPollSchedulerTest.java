package io.github.pinpols.batch.worker.dispatchs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.dispatchs.config.DispatchReceiptPollProperties;
import io.github.pinpols.batch.worker.dispatchs.domain.PendingReceiptPollRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.StaticApplicationContext;

/** 单元测试：{@link DispatchReceiptPollScheduler#poll()} 的互斥与守卫行为。 */
@DisplayName("回执轮询调度:开关与停机守卫、待轮询行缺失字段的跳过,以及瞬时连通性失败分类")
class DispatchReceiptPollSchedulerTest {

  private DispatchReceiptPollProperties properties;
  private FileDispatchRepository fileDispatchRepository;
  private PlatformFileRecordRepository fileRecords;
  private DispatchReceiptPollScheduler scheduler;

  @BeforeEach
  void setUp() {
    properties = new DispatchReceiptPollProperties();
    fileDispatchRepository = mock(FileDispatchRepository.class);
    fileRecords = mock(PlatformFileRecordRepository.class);
    scheduler = new DispatchReceiptPollScheduler(
        properties,
        fileDispatchRepository,
        new ObjectMapper(),
        fileRecords,
        new SimpleMeterRegistry(),
        new BatchSecurityProperties());
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
