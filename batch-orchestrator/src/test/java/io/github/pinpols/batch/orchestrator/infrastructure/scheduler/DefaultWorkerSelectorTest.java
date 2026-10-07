package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.constants.WorkerCapabilities;
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
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("工作节点选择组件,验证资源标签与能力标签的匹配,大小写处理及非法标签内容的兜底行为")
class DefaultWorkerSelectorTest {

  private static final String TENANT = "default-tenant";
  private static final String GROUP = "EXPORT";

  @Mock
  private WorkerRegistryMapper workerRegistryMapper;

  @Mock
  private ObjectProvider<MeterRegistry> meterRegistryProvider;

  @Mock
  private ObjectProvider<WorkerRegistryCache> workerRegistryCacheProvider;

  private DefaultWorkerSelector selector;
  private final ResourceSchedulerProperties props = new ResourceSchedulerProperties();

  @BeforeEach
  void setUp() {
    selector = new DefaultWorkerSelector(
        workerRegistryMapper, meterRegistryProvider, props, workerRegistryCacheProvider);
    lenient().when(meterRegistryProvider.getIfAvailable()).thenReturn(null);
    lenient().when(workerRegistryCacheProvider.getIfAvailable()).thenReturn(null);
  }

  @Test
  @DisplayName("队列未设置资源标签时,应选中在线的工作节点")
  void shouldMatchWorker_whenQueueHasNoTag() {
    WorkerRegistryEntity worker = worker("w-1", null, null);
    stubCandidates(List.of(worker));

    WorkerRouteModel route = selector.select(request(), queue(null), 5);

    assertThat(route.getAvailable()).isTrue();
    assertThat(route.getWorkerCode()).isEqualTo("w-1");
  }

  @Test
  @DisplayName("队列标签与工作节点的单值资源标签一致时,应选中该工作节点")
  void shouldMatchWorker_whenQueueTagEqualsWorkerTag() {
    WorkerRegistryEntity worker = worker("w-1", "report", null);
    stubCandidates(List.of(worker));

    WorkerRouteModel route = selector.select(request(), queue("report"), 5);

    assertThat(route.getAvailable()).isTrue();
    assertThat(route.getWorkerCode()).isEqualTo("w-1");
  }

  @Test
  @DisplayName("队列标签命中工作节点能力标签数组中的任一项时,应选中该工作节点")
  void shouldMatchWorker_whenQueueTagHitsCapabilityArray() {
    WorkerRegistryEntity worker =
        worker("w-1", null, new JsonbString("[\"report\", \"workflow\"]"));
    stubCandidates(List.of(worker));

    WorkerRouteModel route = selector.select(request(), queue("workflow"), 5);

    assertThat(route.getAvailable()).isTrue();
    assertThat(route.getWorkerCode()).isEqualTo("w-1");
  }

  @Test
  @DisplayName("能力标签与队列标签仅大小写不同时,仍应匹配并选中该工作节点")
  void shouldMatchWorker_whenCapabilityCaseDiffers() {
    WorkerRegistryEntity worker = worker("w-1", null, new JsonbString("[\"Report\"]"));
    stubCandidates(List.of(worker));

    WorkerRouteModel route = selector.select(request(), queue("REPORT"), 5);

    assertThat(route.getAvailable()).isTrue();
  }

  @Test
  @DisplayName("资源标签与能力标签都无法满足队列要求时,应返回无可用工作节点的结果")
  void shouldReturnNoMatch_whenNoTagOrCapabilityMatches() {
    WorkerRegistryEntity worker = worker("w-1", "delivery", new JsonbString("[\"ingest\"]"));
    stubCandidates(List.of(worker));

    WorkerRouteModel route = selector.select(request(), queue("report"), 5);

    assertThat(route.getAvailable()).isFalse();
    assertThat(route.getWorkerCode()).isNull();
  }

  @Test
  @DisplayName("能力标签内容结构非法时,应返回无可用工作节点的结果且不抛出异常")
  void shouldReturnNoMatch_whenCapabilityJsonMalformed() {
    WorkerRegistryEntity worker = worker("w-1", null, new JsonbString("{not-an-array}"));
    stubCandidates(List.of(worker));

    WorkerRouteModel route = selector.select(request(), queue("report"), 5);

    assertThat(route.getAvailable()).isFalse();
  }

  @Test
  @DisplayName("试运行请求要求特定能力时,只能选中显式声明该能力的工作节点")
  void shouldSelectExplicitCapableWorker_whenDryRunRequested() {
    WorkerRegistryEntity unsafe = worker("w-unsafe", null, new JsonbString("[\"PROCESS\"]"));
    WorkerRegistryEntity safe =
        worker("w-safe", null, new JsonbString("[\"PROCESS\", \"dry-run-safe\"]"));
    stubCandidates(List.of(unsafe, safe));
    ResourceSchedulingRequest request = request();
    request.setRequiredCapability(WorkerCapabilities.DRY_RUN_SAFE);

    WorkerRouteModel route = selector.select(request, queue(null), 5);

    assertThat(route.getAvailable()).isTrue();
    assertThat(route.getWorkerCode()).isEqualTo("w-safe");
  }

  @Test
  @DisplayName("试运行要求的能力没有任何工作节点声明时,应返回无可用工作节点的结果")
  void shouldReturnNoMatch_whenDryRunCapabilityMissing() {
    stubCandidates(List.of(worker("w-unsafe", null, new JsonbString("[\"PROCESS\"]"))));
    ResourceSchedulingRequest request = request();
    request.setRequiredCapability(WorkerCapabilities.DRY_RUN_SAFE);

    WorkerRouteModel route = selector.select(request, queue(null), 5);

    assertThat(route.getAvailable()).isFalse();
  }

  @Test
  @DisplayName("请求指定资源档位时,应选中匹配档位的节点池并返回稳定的池标识")
  void shouldSelectStablePoolCode_whenResourceProfileMatches() {
    WorkerRegistryEntity general =
        worker("export-general-pod", null, new JsonbString("[\"report\"]"));
    WorkerRegistryEntity heavy = new WorkerRegistryEntity(
        2L,
        TENANT,
        "export-heavy-pod-a",
        GROUP,
        new JsonbString("[\"report\", \"io-heavy\"]"),
        null,
        WorkerRegistryStatus.ONLINE.code(),
        BatchDateTimeSupport.utcNow(),
        0,
        10,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "export-heavy");
    stubCandidates(List.of(general, heavy));
    ResourceSchedulingRequest request = request();
    request.setResourceProfile("io-heavy");

    WorkerRouteModel route = selector.select(request, queue(null), 5);

    assertThat(route.getAvailable()).isTrue();
    assertThat(route.getWorkerCode()).isEqualTo("export-heavy");
    assertThat(route.getResourceProfile()).isEqualTo("io-heavy");
  }

  @Test
  @DisplayName("作业侧资源档位与队列默认档位不同时,应优先按作业侧档位选择节点池")
  void shouldPreferJobProfile_whenQueueDefaultProfileDiffers() {
    WorkerRegistryEntity queueDefault =
        worker("export-general-pod", "standard", new JsonbString("[\"standard\"]"));
    WorkerRegistryEntity heavy = new WorkerRegistryEntity(
        2L,
        TENANT,
        "export-heavy-pod-a",
        GROUP,
        new JsonbString("[\"io-heavy\"]"),
        null,
        WorkerRegistryStatus.ONLINE.code(),
        BatchDateTimeSupport.utcNow(),
        0,
        10,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "export-heavy");
    stubCandidates(List.of(queueDefault, heavy));
    ResourceSchedulingRequest request = request();
    request.setResourceProfile("io-heavy");

    WorkerRouteModel route = selector.select(request, queue("standard"), 5);

    assertThat(route.getAvailable()).isTrue();
    assertThat(route.getWorkerCode()).isEqualTo("export-heavy");
    assertThat(route.getResourceProfile()).isEqualTo("io-heavy");
  }

  private void stubCandidates(List<WorkerRegistryEntity> candidates) {
    when(workerRegistryMapper.selectByTenantAndWorkerGroupAndStatus(
            TENANT, GROUP, WorkerRegistryStatus.ONLINE.code()))
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
