package io.github.pinpols.batch.console.domain.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.ops.entity.CustomTaskTypeEntity;
import io.github.pinpols.batch.console.domain.ops.mapper.CustomTaskTypeMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsoleCustomTaskTypeQueryServiceTest {

  @Mock private CustomTaskTypeMapper mapper;
  @Mock private ConsoleTenantGuard tenantGuard;

  private ConsoleCustomTaskTypeQueryService service;

  @BeforeEach
  void setUp() {
    service = new ConsoleCustomTaskTypeQueryService(mapper, tenantGuard);
  }

  @Test
  void listActiveResolvesTenantBeforeQuery() {
    CustomTaskTypeEntity entity = entity("tenant_tx_import");
    when(tenantGuard.resolveTenant("tx")).thenReturn("tx");
    when(mapper.selectActiveByTenant("tx")).thenReturn(List.of(entity));

    assertThat(service.listActive("tx")).containsExactly(entity);
  }

  @Test
  void countActiveResolvesTenantBeforeQuery() {
    when(tenantGuard.resolveTenant("tx")).thenReturn("tx");
    when(mapper.countActiveByTenant("tx")).thenReturn(3L);

    assertThat(service.countActive("tx")).isEqualTo(3L);
  }

  @Test
  void detailThrowsWhenMissing() {
    when(tenantGuard.resolveTenant("tx")).thenReturn("tx");
    when(mapper.selectByTenantAndCode("tx", "missing")).thenReturn(null);

    assertThatThrownBy(() -> service.detail("tx", "missing")).isInstanceOf(BizException.class);
  }

  private CustomTaskTypeEntity entity(String code) {
    CustomTaskTypeEntity entity = new CustomTaskTypeEntity();
    entity.setTaskTypeCode(code);
    entity.setStatus("ACTIVE");
    return entity;
  }
}
