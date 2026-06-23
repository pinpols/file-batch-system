package io.github.pinpols.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.ops.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.console.domain.ops.service.ConsoleMyWorkerQueryService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsoleMyWorkerControllerTest {

  @Mock private ConsoleMyWorkerQueryService queryService;
  @Mock private ConsoleResponseFactory responseFactory;

  @InjectMocks private ConsoleMyWorkerController controller;

  @Test
  void listResolvesTenantAndQueriesSelfHostedOnly() {
    WorkerRegistryEntity w = new WorkerRegistryEntity();
    w.setWorkerCode("sdk-1");
    when(queryService.listSelfHosted("tx")).thenReturn(List.of(w));
    when(responseFactory.success(List.of(w))).thenReturn(CommonResponse.success(List.of(w)));

    CommonResponse<List<WorkerRegistryEntity>> resp = controller.list("tx");

    assertThat(resp.data())
        .hasSize(1)
        .extracting(WorkerRegistryEntity::getWorkerCode)
        .contains("sdk-1");
  }

  @Test
  void countReturnsMapperResult() {
    when(queryService.countSelfHosted("tx")).thenReturn(7L);
    when(responseFactory.success(7L)).thenReturn(CommonResponse.success(7L));

    assertThat(controller.count("tx").data()).isEqualTo(7L);
  }
}
