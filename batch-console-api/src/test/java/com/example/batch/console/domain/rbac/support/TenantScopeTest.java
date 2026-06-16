package com.example.batch.console.domain.rbac.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TenantScope.requireTenant 租户作用域非空断言")
class TenantScopeTest {

  @Test
  @DisplayName("非空 tenantId 原样返回")
  void shouldReturnTenantId_whenNonBlank() {
    assertThat(TenantScope.requireTenant("ta")).isEqualTo("ta");
  }

  @Test
  @DisplayName("null tenantId 抛 FORBIDDEN（防未来漏 resolveTenant 静默跨租）")
  void shouldThrowForbidden_whenNull() {
    assertThatThrownBy(() -> TenantScope.requireTenant(null))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.FORBIDDEN);
  }

  @Test
  @DisplayName("空串 tenantId 抛 FORBIDDEN")
  void shouldThrowForbidden_whenEmpty() {
    assertThatThrownBy(() -> TenantScope.requireTenant(""))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.FORBIDDEN);
  }

  @Test
  @DisplayName("全空白 tenantId 抛 FORBIDDEN")
  void shouldThrowForbidden_whenBlank() {
    assertThatThrownBy(() -> TenantScope.requireTenant("   "))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.FORBIDDEN);
  }
}
