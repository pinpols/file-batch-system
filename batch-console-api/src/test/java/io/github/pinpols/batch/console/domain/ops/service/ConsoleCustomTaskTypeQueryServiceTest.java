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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("自定义任务类型查询:租户解析前置, 列表与计数返回查询结果, 详情缺失时抛业务异常")
class ConsoleCustomTaskTypeQueryServiceTest {

  @Mock
  private CustomTaskTypeMapper mapper;

  @Mock
  private ConsoleTenantGuard tenantGuard;

  private ConsoleCustomTaskTypeQueryService service;

  @BeforeEach
  void setUp() {
    service = new ConsoleCustomTaskTypeQueryService(mapper, tenantGuard);
  }

  @Test
  @DisplayName("有效列表:先解析租户再查询, 返回该租户的全部有效类型")
  void shouldResolveTenantBeforeQuery_whenListingActiveTypes() {
    CustomTaskTypeEntity entity = entity("tenant_tx_import");
    when(tenantGuard.resolveTenant("tx")).thenReturn("tx");
    when(mapper.selectActiveByTenant("tx")).thenReturn(List.of(entity));

    assertThat(service.listActive("tx")).containsExactly(entity);
  }

  @Test
  @DisplayName("有效计数:先解析租户再统计, 返回计数结果")
  void shouldResolveTenantBeforeQuery_whenCountingActiveTypes() {
    when(tenantGuard.resolveTenant("tx")).thenReturn("tx");
    when(mapper.countActiveByTenant("tx")).thenReturn(3L);

    assertThat(service.countActive("tx")).isEqualTo(3L);
  }

  @Test
  @DisplayName("详情缺失:未命中记录时抛出业务异常")
  void shouldThrowBizException_whenDetailMissing() {
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
