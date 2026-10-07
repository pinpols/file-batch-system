package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
@DisplayName("默认资源队列管理器: 显式队列命中, 专用队列优选与空结果查询缓存")
class DefaultResourceQueueManagerTest {

  @Mock
  private ResourceQueueMapper mapper;

  private DefaultResourceQueueManager manager;

  @BeforeEach
  void setUp() {
    manager = new DefaultResourceQueueManager(mapper);
  }

  @Test
  @DisplayName("显式指定队列代码且命中启用队列时, 返回该队列对象本身")
  void shouldReturnMatchingQueue_whenExplicitQueueCodeIsEnabled() {
    ResourceQueueEntity importQueue = queue("import-fast", "IMPORT", 1, 10, 10);
    when(mapper.selectByTenantAndEnabled("ta", true))
        .thenReturn(List.of(queue("mixed", "MIXED", 100, 100, 100), importQueue));

    ResourceQueueEntity resolved = manager.resolveQueue(request("import-fast", "IMPORT"));

    assertThat(resolved).isSameAs(importQueue);
  }

  @Test
  @DisplayName("未指定队列代码时, 与工作节点类型一致的专用队列优先于混合回退队列")
  void shouldPreferDedicatedQueue_whenQueueCodeAbsentButWorkerTypeMatches() {
    ResourceQueueEntity mixed = queue("mixed-heavy", "MIXED", 100, 100, 100);
    ResourceQueueEntity dedicated = queue("import-small", "IMPORT", 1, 1, 1);
    when(mapper.selectByTenantAndEnabled("ta", true)).thenReturn(List.of(mixed, dedicated));

    ResourceQueueEntity resolved = manager.resolveQueue(request(null, "IMPORT"));

    assertThat(resolved).isSameAs(dedicated);
  }

  @Test
  @DisplayName("显式指定的队列代码不在启用队列中时返回空结果, 不再回退其它队列")
  void shouldReturnNull_whenExplicitQueueCodeIsNotEnabled() {
    when(mapper.selectByTenantAndEnabled("ta", true))
        .thenReturn(List.of(queue("import", "IMPORT", 1, 1, 1)));

    ResourceQueueEntity resolved = manager.resolveQueue(request("disabled-or-missing", "IMPORT"));

    assertThat(resolved).isNull();
  }

  @Test
  @DisplayName("租户没有启用队列时返回空结果, 由后续调度逻辑走默认语义")
  void shouldReturnNull_whenTenantHasNoEnabledQueue() {
    when(mapper.selectByTenantAndEnabled("ta", true)).thenReturn(List.of());

    ResourceQueueEntity resolved = manager.resolveQueue(request(null, "IMPORT"));

    assertThat(resolved).isNull();
  }

  @Test
  @DisplayName("同一租户短时间重复解析且结果为空时, 数据库只被查询一次")
  void shouldQueryDatabaseOnce_whenEmptyQueueResolvedRepeatedly() {
    when(mapper.selectByTenantAndEnabled("ta", true)).thenReturn(List.of());

    assertThat(manager.resolveQueue(request(null, "IMPORT"))).isNull();
    assertThat(manager.resolveQueue(request(null, "IMPORT"))).isNull();

    verify(mapper, times(1)).selectByTenantAndEnabled("ta", true);
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
