package io.github.pinpols.batch.console.application.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.console.application.contract.query.WorkerRegistryQueryRequest;
import io.github.pinpols.batch.console.domain.ops.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.console.domain.ops.mapper.WorkerRegistryMapper;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("控制台运维查询服务")
class ConsoleOpsQueryServiceTest {

  @Mock
  private WorkerRegistryMapper workerRegistryMapper;

  @Mock
  private LocalizedErrorRenderer localizedErrorRenderer;

  @Mock
  private BatchTimezoneProvider timezoneProvider;

  @Test
  @DisplayName("查询 Worker 列表时返回已持久化的执行器能力")
  void shouldIncludeWorkerTaskCapabilities_whenLoadingWorkerList() {
    WorkerRegistryEntity worker = new WorkerRegistryEntity();
    worker.setId(1L);
    worker.setTenantId("tenant-a");
    worker.setWorkerCode("atomic-1");
    worker.setWorkerGroup("atomic");
    worker.setStatus("ONLINE");
    worker.setCapabilityTagsJson("[\"atomic\"]");
    worker.setResourceTag("disk");
    worker.setTaskCapabilitiesJson("[{\"taskType\":\"file_sha256\",\"resourceKinds\":[\"DISK\"],"
        + "\"idempotent\":true,\"cancellable\":false,"
        + "\"recommendedTimeoutMillis\":300000}]");
    when(workerRegistryMapper.selectByQuery(any())).thenReturn(List.of(worker));
    when(workerRegistryMapper.countByQuery(any())).thenReturn(1L);
    ConsoleOpsQueryMappers mappers = new ConsoleOpsQueryMappers(
        null, null, null, null, null, null, workerRegistryMapper, null, null, null, null, null);
    TenantIdResolver tenantResolver = requestTenantId -> requestTenantId;
    ConsoleOpsQueryService service = new ConsoleOpsQueryService(
        tenantResolver, mappers, localizedErrorRenderer, timezoneProvider);
    WorkerRegistryQueryRequest request = new WorkerRegistryQueryRequest();
    request.setTenantId("tenant-a");

    var response = service.workers(request).items().getFirst();

    assertThat(response.capabilityTags()).isEqualTo("[\"atomic\"]");
    assertThat(response.resourceTag()).isEqualTo("disk");
    assertThat(response.taskCapabilities()).singleElement().satisfies(capability -> {
      assertThat(capability.taskType()).isEqualTo("file_sha256");
      assertThat(capability.resourceKinds()).containsExactly("DISK");
      assertThat(capability.recommendedTimeoutMillis()).isEqualTo(300_000L);
    });
  }
}
