package io.github.pinpols.batch.console.domain.file.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.config.ConsolePipelineProgressDirtyProperties;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsolePipelineProgressDirtyEventResponse;
import io.github.pinpols.batch.console.domain.file.mapper.ConsolePipelineProgressDirtyMapper;
import io.github.pinpols.batch.console.domain.file.mapper.ConsolePipelineProgressDirtyMapper.DirtyCursor;
import io.github.pinpols.batch.console.domain.file.view.PipelineProgressDirtyView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@DisplayName("Pipeline 进度 dirty 事件发布失败后重试")
@ExtendWith(OutputCaptureExtension.class)
class ConsolePipelineProgressDirtyPublisherTest {

  @Test
  @DisplayName("节流中的更新保留扫描位置，窗口结束后仍发布且不越过后续实例")
  void shouldRetryThrottledUpdateBeforeAdvancingToLaterRows() {
    ConsolePipelineProgressDirtyMapper mapper = mock(ConsolePipelineProgressDirtyMapper.class);
    ConsoleRealtimeEventPort eventPort = mock(ConsoleRealtimeEventPort.class);
    ConsolePipelineProgressDirtyProperties properties =
        new ConsolePipelineProgressDirtyProperties();
    properties.setInitialDelayMillis(60_000L);
    properties.setIntervalMillis(1_000L);
    properties.setThrottleMillis(1_000L);
    properties.setLookbackOverlapMillis(0L);
    properties.setBatchSize(2);
    Instant updatedAt = Instant.now().minusSeconds(1);
    PipelineProgressDirtyView first =
        new PipelineProgressDirtyView("tenant-a", 1L, null, updatedAt);
    PipelineProgressDirtyView newer =
        new PipelineProgressDirtyView("tenant-a", 1L, null, updatedAt.plusMillis(1));
    PipelineProgressDirtyView later =
        new PipelineProgressDirtyView("tenant-a", 2L, null, updatedAt.plusMillis(2));
    AtomicReference<List<PipelineProgressDirtyView>> available =
        new AtomicReference<>(List.of(first));
    when(mapper.selectUpdatedSince(any(), nullable(DirtyCursor.class), eq(2)))
        .thenAnswer(invocation -> {
          Instant since = invocation.getArgument(0);
          DirtyCursor after = invocation.getArgument(1);
          return available.get().stream()
              .filter(row -> !row.updatedAt().isBefore(since))
              .filter(row -> after == null || row.updatedAt().isAfter(after.updatedAt()))
              .limit(2)
              .toList();
        });
    ConsolePipelineProgressDirtyPublisher publisher =
        new ConsolePipelineProgressDirtyPublisher(mapper, eventPort, properties);
    publisher.start();
    try {
      publisher.pollSafely();
      available.set(List.of(newer, later));
      publisher.pollSafely();
      verify(eventPort, times(1))
          .publishChanged(
              eq("tenant-a"), eq("pipeline-progress"), eq("pipeline-progress-dirty"), any());

      await()
          .atMost(Duration.ofSeconds(3))
          .pollInterval(Duration.ofMillis(50))
          .untilAsserted(() -> {
            publisher.pollSafely();
            verify(eventPort, times(3))
                .publishChanged(
                    eq("tenant-a"), eq("pipeline-progress"), eq("pipeline-progress-dirty"), any());
          });
    } finally {
      publisher.stop();
    }

    ArgumentCaptor<ConsolePipelineProgressDirtyEventResponse> payloads =
        ArgumentCaptor.forClass(ConsolePipelineProgressDirtyEventResponse.class);
    verify(eventPort, times(3))
        .publishChanged(
            eq("tenant-a"),
            eq("pipeline-progress"),
            eq("pipeline-progress-dirty"),
            payloads.capture());
    assertThat(payloads.getAllValues())
        .extracting(ConsolePipelineProgressDirtyEventResponse::pipelineInstanceId)
        .containsExactly(1L, 1L, 2L);
  }

  @Test
  @DisplayName("同时间戳更新超过页大小时，发布器继续读取下一页")
  void shouldPublishAllRowsAcrossPagesWithSharedUpdateTime() {
    ConsolePipelineProgressDirtyMapper mapper = mock(ConsolePipelineProgressDirtyMapper.class);
    ConsoleRealtimeEventPort eventPort = mock(ConsoleRealtimeEventPort.class);
    ConsolePipelineProgressDirtyProperties properties =
        new ConsolePipelineProgressDirtyProperties();
    properties.setInitialDelayMillis(60_000L);
    properties.setBatchSize(2);
    Instant updatedAt = Instant.now();
    PipelineProgressDirtyView first =
        new PipelineProgressDirtyView("tenant-a", 1L, null, updatedAt);
    PipelineProgressDirtyView second =
        new PipelineProgressDirtyView("tenant-a", 2L, null, updatedAt);
    PipelineProgressDirtyView third =
        new PipelineProgressDirtyView("tenant-a", 3L, null, updatedAt);
    when(mapper.selectUpdatedSince(any(), nullable(DirtyCursor.class), eq(2)))
        .thenAnswer(invocation -> {
          DirtyCursor after = invocation.getArgument(1);
          return after == null ? List.of(first, second) : List.of(third);
        });
    ConsolePipelineProgressDirtyPublisher publisher =
        new ConsolePipelineProgressDirtyPublisher(mapper, eventPort, properties);
    publisher.start();
    try {
      publisher.pollSafely();
      publisher.pollSafely();
    } finally {
      publisher.stop();
    }

    verify(eventPort, times(3))
        .publishChanged(
            eq("tenant-a"), eq("pipeline-progress"), eq("pipeline-progress-dirty"), any());
  }

  @Test
  @DisplayName("发布端口失败时，同一进度更新在下次扫描重试")
  void shouldRetrySameUpdateAfterPublishFailure(CapturedOutput output) {
    ConsolePipelineProgressDirtyMapper mapper = mock(ConsolePipelineProgressDirtyMapper.class);
    ConsoleRealtimeEventPort eventPort = mock(ConsoleRealtimeEventPort.class);
    ConsolePipelineProgressDirtyProperties properties =
        new ConsolePipelineProgressDirtyProperties();
    properties.setInitialDelayMillis(60_000L);
    PipelineProgressDirtyView row =
        new PipelineProgressDirtyView("tenant-a", 7L, 9L, Instant.now());
    when(mapper.selectUpdatedSince(any(), nullable(DirtyCursor.class), eq(500)))
        .thenReturn(List.of(row));
    doThrow(new IllegalStateException("password=probe-secret"))
        .doNothing()
        .when(eventPort)
        .publishChanged(
            eq("tenant-a"), eq("pipeline-progress"), eq("pipeline-progress-dirty"), any());
    ConsolePipelineProgressDirtyPublisher publisher =
        new ConsolePipelineProgressDirtyPublisher(mapper, eventPort, properties);
    publisher.start();
    try {
      publisher.pollSafely();
      publisher.pollSafely();
    } finally {
      publisher.stop();
    }

    verify(eventPort, times(2))
        .publishChanged(
            eq("tenant-a"), eq("pipeline-progress"), eq("pipeline-progress-dirty"), any());
    assertThat(output.getOut()).doesNotContain("probe-secret");
  }
}
