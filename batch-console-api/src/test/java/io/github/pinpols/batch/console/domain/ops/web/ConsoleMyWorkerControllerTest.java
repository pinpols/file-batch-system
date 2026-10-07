package io.github.pinpols.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.WorkerRegistryResponse;
import io.github.pinpols.batch.console.domain.ops.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.console.domain.ops.service.ConsoleMyWorkerQueryService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("我的 Worker 接口:列表只查自托管 Worker, 弃用计数端点保持兼容")
class ConsoleMyWorkerControllerTest {

  @Mock
  private ConsoleMyWorkerQueryService queryService;

  @Mock
  private ConsoleResponseFactory responseFactory;

  @InjectMocks
  private ConsoleMyWorkerController controller;

  @Test
  @DisplayName("列表查询:先解析租户再查自托管 Worker, 返回结果按 Worker 编码回填")
  void shouldResolveTenantAndListSelfHosted_whenListingMyWorkers() {
    WorkerRegistryEntity w = new WorkerRegistryEntity();
    w.setWorkerCode("sdk-1");
    when(queryService.listSelfHosted("tx")).thenReturn(List.of(w));
    WorkerRegistryResponse expected = WorkerRegistryResponse.from(w);
    when(responseFactory.success(List.of(expected)))
        .thenReturn(CommonResponse.success(List.of(expected)));

    CommonResponse<List<WorkerRegistryResponse>> resp = controller.list("tx");

    assertThat(resp.data())
        .hasSize(1)
        .extracting(WorkerRegistryResponse::workerCode)
        .contains("sdk-1");
  }

  @Test
  @DisplayName("兼容计数查询:返回查询服务的统计结果")
  @SuppressWarnings("removal")
  void shouldReturnMapperCount_whenCountingSelfHostedWorkers() {
    when(queryService.countSelfHosted("tx")).thenReturn(7L);
    when(responseFactory.success(7L)).thenReturn(CommonResponse.success(7L));

    assertThat(controller.count("tx").data()).isEqualTo(7L);
  }
}
