package com.example.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.ops.entity.CustomTaskTypeEntity;
import com.example.batch.console.domain.ops.mapper.CustomTaskTypeMapper;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.service.ConsoleResponseFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsoleCustomTaskTypeControllerTest {

  @Mock private CustomTaskTypeMapper mapper;
  @Mock private ConsoleTenantGuard tenantGuard;
  @Mock private ConsoleResponseFactory responseFactory;

  @InjectMocks private ConsoleCustomTaskTypeController controller;

  private CustomTaskTypeEntity entity(String code) {
    CustomTaskTypeEntity e = new CustomTaskTypeEntity();
    e.setTaskTypeCode(code);
    e.setStatus("ACTIVE");
    return e;
  }

  @Test
  void listResolvesTenantAndQueriesActiveOnly() {
    when(tenantGuard.resolveTenant("tx")).thenReturn("tx");
    CustomTaskTypeEntity e = entity("tenant_tx_import");
    when(mapper.selectActiveByTenant("tx")).thenReturn(List.of(e));
    when(responseFactory.success(List.of(e))).thenReturn(CommonResponse.success(List.of(e)));

    CommonResponse<List<CustomTaskTypeEntity>> resp = controller.list("tx");

    assertThat(resp.data())
        .hasSize(1)
        .extracting(CustomTaskTypeEntity::getTaskTypeCode)
        .contains("tenant_tx_import");
  }

  @Test
  void countReturnsMapperResult() {
    when(tenantGuard.resolveTenant("tx")).thenReturn("tx");
    when(mapper.countActiveByTenant("tx")).thenReturn(3L);
    when(responseFactory.success(3L)).thenReturn(CommonResponse.success(3L));

    assertThat(controller.count("tx").data()).isEqualTo(3L);
  }

  @Test
  void detailReturnsEntityWhenFound() {
    when(tenantGuard.resolveTenant("tx")).thenReturn("tx");
    CustomTaskTypeEntity e = entity("tenant_tx_import");
    when(mapper.selectByTenantAndCode("tx", "tenant_tx_import")).thenReturn(e);
    when(responseFactory.success(e)).thenReturn(CommonResponse.success(e));

    assertThat(controller.detail("tenant_tx_import", "tx").data().getTaskTypeCode())
        .isEqualTo("tenant_tx_import");
  }

  @Test
  void detailThrowsNotFoundWhenMissing() {
    when(tenantGuard.resolveTenant("tx")).thenReturn("tx");
    when(mapper.selectByTenantAndCode("tx", "missing")).thenReturn(null);

    assertThatThrownBy(() -> controller.detail("missing", "tx")).isInstanceOf(BizException.class);
  }
}
