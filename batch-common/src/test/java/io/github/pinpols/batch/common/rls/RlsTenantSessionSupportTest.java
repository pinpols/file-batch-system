package io.github.pinpols.batch.common.rls;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 单测 {@link RlsTenantSessionSupport} 的纯函数路径——形态白名单(P2-2 纵深防御层)。
 *
 * <p>实际 set_config 语义走 {@code RlsTenantIsolationIntegrationTest}(连真 PG)。
 */
class RlsTenantSessionSupportTest {

  @Test
  void tenantIdPattern_acceptsAsciiAlnumDashUnderscore() {
    // 准备 / 执行 / 断言
    assertThat(RlsTenantSessionSupport.TENANT_ID_PATTERN.matcher("ta").matches()).isTrue();
    assertThat(RlsTenantSessionSupport.TENANT_ID_PATTERN.matcher("Tenant-01_b").matches()).isTrue();
    assertThat(RlsTenantSessionSupport.TENANT_ID_PATTERN.matcher("a".repeat(64)).matches())
        .isTrue();
  }

  @Test
  void tenantIdPattern_rejectsInjectionShapes() {
    // 单引号 / 反斜杠 / 注释 / 分号 / 控制字符 / Unicode escape 全拒
    assertThat(RlsTenantSessionSupport.TENANT_ID_PATTERN.matcher("ta'; DROP--").matches())
        .isFalse();
    assertThat(RlsTenantSessionSupport.TENANT_ID_PATTERN.matcher("ta\\u0027").matches()).isFalse();
    assertThat(RlsTenantSessionSupport.TENANT_ID_PATTERN.matcher("ta;b").matches()).isFalse();
    assertThat(RlsTenantSessionSupport.TENANT_ID_PATTERN.matcher("ta b").matches()).isFalse();
    assertThat(RlsTenantSessionSupport.TENANT_ID_PATTERN.matcher("\n").matches()).isFalse();
    assertThat(RlsTenantSessionSupport.TENANT_ID_PATTERN.matcher("").matches()).isFalse();
    // 65 字符超长拒
    assertThat(RlsTenantSessionSupport.TENANT_ID_PATTERN.matcher("a".repeat(65)).matches())
        .isFalse();
  }
}
