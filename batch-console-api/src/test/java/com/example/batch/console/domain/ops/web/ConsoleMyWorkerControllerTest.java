package com.example.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.ops.entity.WorkerRegistryEntity;
import com.example.batch.console.domain.ops.mapper.WorkerRegistryMapper;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.service.ConsoleResponseFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsoleMyWorkerControllerTest {

  @Mock private WorkerRegistryMapper mapper;
  @Mock private ConsoleTenantGuard tenantGuard;
  @Mock private ConsoleResponseFactory responseFactory;

  @InjectMocks private ConsoleMyWorkerController controller;

  @Test
  void listResolvesTenantAndQueriesSelfHostedOnly() {
    when(tenantGuard.resolveTenant("tx")).thenReturn("tx");
    WorkerRegistryEntity w = new WorkerRegistryEntity();
    w.setWorkerCode("sdk-1");
    when(mapper.selectSelfHostedByTenant("tx")).thenReturn(List.of(w));
    when(responseFactory.success(List.of(w))).thenReturn(CommonResponse.success(List.of(w)));

    CommonResponse<List<WorkerRegistryEntity>> resp = controller.list("tx");

    assertThat(resp.data())
        .hasSize(1)
        .extracting(WorkerRegistryEntity::getWorkerCode)
        .contains("sdk-1");
  }

  @Test
  void countReturnsMapperResult() {
    when(tenantGuard.resolveTenant("tx")).thenReturn("tx");
    when(mapper.countSelfHostedByTenant("tx")).thenReturn(7L);
    when(responseFactory.success(7L)).thenReturn(CommonResponse.success(7L));

    assertThat(controller.count("tx").data()).isEqualTo(7L);
  }
}
