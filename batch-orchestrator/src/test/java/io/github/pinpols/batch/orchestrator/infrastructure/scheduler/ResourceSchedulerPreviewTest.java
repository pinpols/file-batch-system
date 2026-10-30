package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.model.WorkerRouteModel;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.scheduler.ConcurrencyLimiter;
import io.github.pinpols.batch.orchestrator.application.scheduler.DispatchAdmissionLimiter;
import io.github.pinpols.batch.orchestrator.application.scheduler.DownstreamAdmissionGuard;
import io.github.pinpols.batch.orchestrator.application.scheduler.PartitionThrottle;
import io.github.pinpols.batch.orchestrator.application.scheduler.PriorityScheduler;
import io.github.pinpols.batch.orchestrator.application.scheduler.ResourceQueueManager;
import io.github.pinpols.batch.orchestrator.application.scheduler.WorkerSelector;
import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("等待分区候选预评估: 排序不消费容量和派发令牌,正式准入只扣一次")
class ResourceSchedulerPreviewTest {

  @Mock
  private ResourceQueueManager resourceQueueManager;

  @Mock
  private ConcurrencyLimiter concurrencyLimiter;

  @Mock
  private PartitionThrottle partitionThrottle;

  @Mock
  private DispatchAdmissionLimiter dispatchAdmissionLimiter;

  @Mock
  private DownstreamAdmissionGuard downstreamAdmissionGuard;

  @Mock
  private WorkerSelector workerSelector;

  @Mock
  private PriorityScheduler priorityScheduler;

  @Mock
  private OrchestratorConfigCacheService configCacheService;

  @Mock
  private JobInstanceMapper jobInstanceMapper;

  @Mock
  private JobPartitionMapper jobPartitionMapper;

  @Mock
  private BatchTimezoneProvider timezoneProvider;

  @Mock
  private BatchDateTimeSupport dateTimeSupport;

  @Spy
  private ResourceSchedulerProperties resourceSchedulerProperties;

  @InjectMocks
  private DefaultResourceScheduler scheduler;

  @Test
  @DisplayName("反复评估等待候选保留路由与公平分数,只在实际派发时扣一次额度")
  void shouldConsumeAdmissionOnce_whenPreviewedRepeatedlyBeforeDispatch() {
    ResourceSchedulingRequest request = new ResourceSchedulingRequest();
    request.setTenantId("tenant-preview");
    request.setWorkerGroup("IMPORT");
    request.setWaitingSince(Instant.parse("2026-10-22T00:00:00Z"));
    WorkerRouteModel route = new WorkerRouteModel();
    route.setAvailable(true);
    when(priorityScheduler.resolvePriority(any(), any())).thenReturn(5);
    when(priorityScheduler.resolvePriorityBand(5)).thenReturn("MEDIUM");
    when(workerSelector.select(any(), any(), any())).thenReturn(route);
    when(downstreamAdmissionGuard.check(request)).thenReturn(ResourceCheck.allow());
    when(dateTimeSupport.nowInstant()).thenReturn(Instant.parse("2026-10-22T00:01:00Z"));

    for (int attempt = 0; attempt < 3; attempt++) {
      var candidate = scheduler.preview(request);
      assertThat(candidate.isDispatchable()).isTrue();
      assertThat(candidate.getRoute()).isSameAs(route);
      assertThat(candidate.getFairnessScore()).isNotNull();
    }
    verifyNoInteractions(concurrencyLimiter, partitionThrottle, dispatchAdmissionLimiter);

    when(concurrencyLimiter.check(any(), any())).thenReturn(ResourceCheck.allow());
    when(partitionThrottle.check(any(), any())).thenReturn(ResourceCheck.allow());
    when(dispatchAdmissionLimiter.check(any(), any())).thenReturn(ResourceCheck.allow());
    assertThat(scheduler.schedule(request).isDispatchable()).isTrue();

    verify(concurrencyLimiter, times(1)).check(any(), any());
    verify(partitionThrottle, times(1)).check(any(), any());
    verify(dispatchAdmissionLimiter, times(1)).check(any(), any());
  }
}
