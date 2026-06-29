package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.domain.entity.ResourceQueueEntity;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.mapper.ResourceQueueMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 单元测试：资源池解析只从启用队列中选,且专用队列优先于 MIXED 回退队列。 */
@ExtendWith(MockitoExtension.class)
class DefaultResourceQueueManagerTest {

  @Mock private ResourceQueueMapper mapper;

  private DefaultResourceQueueManager manager;

  @BeforeEach
  void setUp() {
    manager = new DefaultResourceQueueManager(mapper);
  }

  @Test
  @DisplayName("显式 queueCode 命中启用队列 → 返回该队列")
  void explicitQueueCodeSelectsEnabledQueue() {
    ResourceQueueEntity importQueue = queue("import-fast", "IMPORT", 1, 10, 10);
    when(mapper.selectByTenantAndEnabled("ta", true))
        .thenReturn(List.of(queue("mixed", "MIXED", 100, 100, 100), importQueue));

    ResourceQueueEntity resolved = manager.resolveQueue(request("import-fast", "IMPORT"));

    assertThat(resolved).isSameAs(importQueue);
  }

  @Test
  @DisplayName("未显式 queueCode → workerType 专用队列优先于 MIXED")
  void exactWorkerTypeWinsOverMixedFallback() {
    ResourceQueueEntity mixed = queue("mixed-heavy", "MIXED", 100, 100, 100);
    ResourceQueueEntity dedicated = queue("import-small", "IMPORT", 1, 1, 1);
    when(mapper.selectByTenantAndEnabled("ta", true)).thenReturn(List.of(mixed, dedicated));

    ResourceQueueEntity resolved = manager.resolveQueue(request(null, "IMPORT"));

    assertThat(resolved).isSameAs(dedicated);
  }

  @Test
  @DisplayName("显式 queueCode 未命中启用队列 → 返回 null")
  void explicitQueueMissingFromEnabledListReturnsNull() {
    when(mapper.selectByTenantAndEnabled("ta", true))
        .thenReturn(List.of(queue("import", "IMPORT", 1, 1, 1)));

    ResourceQueueEntity resolved = manager.resolveQueue(request("disabled-or-missing", "IMPORT"));

    assertThat(resolved).isNull();
  }

  @Test
  @DisplayName("租户没有启用队列 → 返回 null,由后续调度逻辑走默认语义")
  void noEnabledQueueReturnsNull() {
    when(mapper.selectByTenantAndEnabled("ta", true)).thenReturn(List.of());

    ResourceQueueEntity resolved = manager.resolveQueue(request(null, "IMPORT"));

    assertThat(resolved).isNull();
  }

  private static ResourceSchedulingRequest request(String queueCode, String workerType) {
    ResourceSchedulingRequest request = new ResourceSchedulingRequest();
    request.setTenantId("ta");
    request.setQueueCode(queueCode);
    request.setWorkerType(workerType);
    return request;
  }

  private static ResourceQueueEntity queue(
      String queueCode,
      String queueType,
      Integer fairShareWeight,
      Integer maxRunningJobs,
      Integer maxRunningPartitions) {
    return new ResourceQueueEntity(
        null,
        "ta",
        queueCode,
        queueCode,
        queueType,
        maxRunningJobs,
        maxRunningPartitions,
        null,
        null,
        null,
        null,
        fairShareWeight,
        null,
        null,
        null,
        null,
        true);
  }
}
