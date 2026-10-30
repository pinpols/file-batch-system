package io.github.pinpols.batch.console.domain.workflow.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeSubscriptionPort;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.WorkflowExecutionProgressResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
@DisplayName("运行进度推送共享订阅与失败恢复")
class WorkflowExecutionPushServiceTest {
  @Mock
  private WorkflowExecutionQueryService queries;

  @Mock
  private ConsoleRealtimeSubscriptionPort streams;

  @Mock
  private ConsoleRealtimeEventPort events;

  @Mock
  private TaskScheduler scheduler;

  @Mock
  private ScheduledFuture<?> future;

  private WorkflowExecutionPushService service;

  @BeforeEach
  void setUp() {
    service = new WorkflowExecutionPushService(queries, streams, events, scheduler);
  }

  @Test
  @DisplayName("同租户同运行共享采样，不因生成时间变化重复推送")
  void shouldShareSampling_whenSubscribersWatchSameRun() {
    var initial = snapshot("RUNNING", 0);
    when(queries.progress("ta", 7L)).thenReturn(initial);
    when(streams.subscribe("ta", "workflow-execution-7", null, null, null))
        .thenAnswer(call -> new SseEmitter());
    doReturn(future)
        .when(scheduler)
        .scheduleWithFixedDelay(any(Runnable.class), eq(Duration.ofSeconds(2)));
    service.subscribe("ta", 7L);
    service.subscribe("ta", 7L);
    var task = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).scheduleWithFixedDelay(task.capture(), eq(Duration.ofSeconds(2)));
    when(queries.progressForAuthorizedTenant("ta", 7L))
        .thenReturn(snapshot("RUNNING", 1), snapshot("SUCCESS", 2));
    task.getValue().run();
    verify(events, times(2))
        .publishChanged("ta", "workflow-execution-7", "workflow-execution-progress", initial);
    task.getValue().run();
    verify(events)
        .publishChanged(
            "ta", "workflow-execution-7", "workflow-execution-progress", snapshot("SUCCESS", 2));
    service.stop();
    verify(future).cancel(false);
  }

  @Test
  @DisplayName("推送失败不能提前推进快照水位，下一采样仍重发")
  void shouldRetrySnapshot_whenPublishFails() {
    var initial = snapshot("RUNNING", 0);
    var finished = snapshot("SUCCESS", 1);
    when(queries.progress("ta", 7L)).thenReturn(initial);
    when(streams.subscribe("ta", "workflow-execution-7", null, null, null))
        .thenReturn(new SseEmitter());
    doReturn(future)
        .when(scheduler)
        .scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
    service.subscribe("ta", 7L);
    var task = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).scheduleWithFixedDelay(task.capture(), any(Duration.class));
    when(queries.progressForAuthorizedTenant("ta", 7L)).thenReturn(finished);
    doThrow(new IllegalStateException("transient"))
        .doNothing()
        .when(events)
        .publishChanged("ta", "workflow-execution-7", "workflow-execution-progress", finished);
    task.getValue().run();
    task.getValue().run();
    verify(events, times(2))
        .publishChanged("ta", "workflow-execution-7", "workflow-execution-progress", finished);
    service.stop();
  }

  @Test
  @DisplayName("未授权请求不能创建后台采样或连接")
  void shouldRejectBeforeSubscribing_whenTenantAccessDenied() {
    when(queries.progress("tb", 7L)).thenThrow(new IllegalArgumentException("denied"));
    assertThatThrownBy(() -> service.subscribe("tb", 7L))
        .isInstanceOf(IllegalArgumentException.class);
    Mockito.verifyNoInteractions(scheduler, streams, events);
  }

  @Test
  @DisplayName("后台调度拒绝后释放观察槽，后续订阅可重新创建采样")
  void shouldRemoveWatch_whenSchedulerRejects() {
    when(queries.progress("ta", 7L)).thenReturn(snapshot("RUNNING", 0));
    when(streams.subscribe("ta", "workflow-execution-7", null, null, null))
        .thenReturn(new SseEmitter());
    doThrow(new IllegalStateException("shutdown"))
        .doReturn(future)
        .when(scheduler)
        .scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
    assertThatThrownBy(() -> service.subscribe("ta", 7L)).isInstanceOf(IllegalStateException.class);
    service.subscribe("ta", 7L);
    verify(scheduler, times(2)).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
    service.stop();
    verify(future).cancel(false);
  }

  @Test
  @DisplayName("根状态未变时开始和结束时间各自变化仍推送")
  void shouldPushRootTimeChangesWhenStatusIsUnchanged() {
    var initial = snapshot("SUCCESS", 0);
    Instant startedAt = Instant.parse("2026-10-26T02:00:00Z");
    Instant finishedAt = Instant.parse("2026-10-26T02:01:00Z");
    var started = snapshot("SUCCESS", 1, startedAt, null);
    var finished = snapshot("SUCCESS", 2, startedAt, finishedAt);
    when(queries.progress("ta", 7L)).thenReturn(initial);
    when(streams.subscribe("ta", "workflow-execution-7", null, null, null))
        .thenReturn(new SseEmitter());
    doReturn(future)
        .when(scheduler)
        .scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
    service.subscribe("ta", 7L);
    var task = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).scheduleWithFixedDelay(task.capture(), any(Duration.class));
    when(queries.progressForAuthorizedTenant("ta", 7L)).thenReturn(started, finished);
    task.getValue().run();
    task.getValue().run();
    verify(events)
        .publishChanged("ta", "workflow-execution-7", "workflow-execution-progress", started);
    verify(events)
        .publishChanged("ta", "workflow-execution-7", "workflow-execution-progress", finished);
    service.stop();
  }

  private static WorkflowExecutionProgressResponse snapshot(String status, int second) {
    return snapshot(status, second, null, null);
  }

  private static WorkflowExecutionProgressResponse snapshot(
      String status, int second, Instant started, Instant finished) {
    return WorkflowExecutionProgressResponse.builder()
        .tenantId("ta")
        .workflowRunId(7L)
        .workflowDefinitionId(3L)
        .workflowVersion(1)
        .runStatus(status)
        .startedAt(started)
        .finishedAt(finished)
        .snapshotAvailable(true)
        .generatedAt(Instant.parse("2026-10-25T10:00:0" + second + "Z"))
        .nodes(List.of())
        .edges(List.of())
        .build();
  }
}
