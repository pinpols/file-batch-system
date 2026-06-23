package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.common.model.WorkerRouteModel;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.config.ResourceSchedulerProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.ResourceQueueEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.domain.value.JsonbString;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 覆盖 resource_tag 匹配逻辑，重点验证 {@code capability_tags} JSONB 数组作为 worker 侧多能力声明 可以命中 queue 的 tag
 * 要求——防止 selector 在单值 {@code resource_tag} 外静默阻塞。
 */
@ExtendWith(MockitoExtension.class)
class DefaultWorkerSelectorTest {

  private static final String TENANT = "default-tenant";
  private static final String GROUP = "EXPORT";

  @Mock private WorkerRegistryMapper workerRegistryMapper;

  @Mock private ObjectProvider<MeterRegistry> meterRegistryProvider;

  @Mock private ObjectProvider<WorkerRegistryCache> workerRegistryCacheProvider;

  private DefaultWorkerSelector selector;
  private final ResourceSchedulerProperties props = new ResourceSchedulerProperties();

  @BeforeEach
  void setUp() {
    selector =
        new DefaultWorkerSelector(
            workerRegistryMapper, meterRegistryProvider, props, workerRegistryCacheProvider);
    lenient().when(meterRegistryProvider.getIfAvailable()).thenReturn(null);
    lenient().when(workerRegistryCacheProvider.getIfAvailable()).thenReturn(null);
  }

  @Test
  void matchesWhenQueueHasNoTag() {
    WorkerRegistryEntity worker = worker("w-1", null, null);
    stubCandidates(List.of(worker));

    WorkerRouteModel route = selector.select(request(), queue(null), 5);

    assertThat(route.getAvailable()).isTrue();
    assertThat(route.getWorkerCode()).isEqualTo("w-1");
  }

  @Test
  void matchesWhenQueueTagEqualsWorkerSingleTag() {
    WorkerRegistryEntity worker = worker("w-1", "report", null);
    stubCandidates(List.of(worker));

    WorkerRouteModel route = selector.select(request(), queue("report"), 5);

    assertThat(route.getAvailable()).isTrue();
    assertThat(route.getWorkerCode()).isEqualTo("w-1");
  }

  @Test
  void matchesWhenQueueTagHitsCapabilityTagsArray() {
    WorkerRegistryEntity worker =
        worker("w-1", null, new JsonbString("[\"report\", \"workflow\"]"));
    stubCandidates(List.of(worker));

    WorkerRouteModel route = selector.select(request(), queue("workflow"), 5);

    assertThat(route.getAvailable()).isTrue();
    assertThat(route.getWorkerCode()).isEqualTo("w-1");
  }

  @Test
  void capabilityTagsMatchIsCaseInsensitive() {
    WorkerRegistryEntity worker = worker("w-1", null, new JsonbString("[\"Report\"]"));
    stubCandidates(List.of(worker));

    WorkerRouteModel route = selector.select(request(), queue("REPORT"), 5);

    assertThat(route.getAvailable()).isTrue();
  }

  @Test
  void returnsNoMatchWhenNeitherResourceTagNorCapabilityMatches() {
    WorkerRegistryEntity worker = worker("w-1", "delivery", new JsonbString("[\"ingest\"]"));
    stubCandidates(List.of(worker));

    WorkerRouteModel route = selector.select(request(), queue("report"), 5);

    assertThat(route.getAvailable()).isFalse();
    assertThat(route.getWorkerCode()).isNull();
  }

  @Test
  void malformedCapabilityTagsJsonDoesNotCrashSelector() {
    WorkerRegistryEntity worker = worker("w-1", null, new JsonbString("{not-an-array}"));
    stubCandidates(List.of(worker));

    WorkerRouteModel route = selector.select(request(), queue("report"), 5);

    assertThat(route.getAvailable()).isFalse();
  }

  private void stubCandidates(List<WorkerRegistryEntity> candidates) {
    when(workerRegistryMapper.selectByTenantAndWorkerGroupAndStatus(
            eq(TENANT), eq(GROUP), eq(WorkerRegistryStatus.ONLINE.code())))
        .thenReturn(candidates);
  }

  private static ResourceSchedulingRequest request() {
    ResourceSchedulingRequest req = new ResourceSchedulingRequest();
    req.setTenantId(TENANT);
    req.setWorkerGroup(GROUP);
    req.setWorkerType("EXPORT");
    return req;
  }

  private static ResourceQueueEntity queue(String resourceTag) {
    return new ResourceQueueEntity(
        1L,
        TENANT,
        "export_queue",
        "export",
        "STANDARD",
        10,
        20,
        0,
        GROUP,
        resourceTag,
        "FIFO",
        1,
        null,
        0,
        "NONE",
        0,
        Boolean.TRUE);
  }

  private static WorkerRegistryEntity worker(
      String code, String resourceTag, JsonbString capabilityTags) {
    return new WorkerRegistryEntity(
        1L,
        TENANT,
        code,
        GROUP,
        capabilityTags,
        resourceTag,
        WorkerRegistryStatus.ONLINE.code(),
        BatchDateTimeSupport.utcNow(),
        0,
        10,
        null,
        null);
  }
}
