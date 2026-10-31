package io.github.pinpols.batch.common.rls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 单测 {@link RlsTenantSessionSupport} 的纯函数路径——形态白名单(P2-2 纵深防御层)。
 *
 * <p>实际 set_config 语义走 {@code RlsTenantIsolationIntegrationTest}(连真 PG)。
 */
@DisplayName("租户会话加固:缺少或非法租户上下文时在建立连接前快速失败,以及租户标识形态白名单")
class RlsTenantSessionSupportTest {

  @Test
  @DisplayName("缺少租户上下文时在打开连接前失败关闭")
  void applyWithoutTenantContext_failsClosedBeforeOpeningConnection() {
    RlsTenantContextHolder.clear();

    assertThatThrownBy(() -> RlsTenantSessionSupport.applyIfPresent(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tenant context is missing");
  }

  @Test
  @DisplayName("租户上下文形态非法时在打开连接前失败关闭")
  void applyWithInvalidTenantContext_failsClosedBeforeOpeningConnection() {
    RlsTenantContextHolder.set("tenant with spaces");
    try {
      assertThatThrownBy(() -> RlsTenantSessionSupport.applyIfPresent(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("invalid shape");
    } finally {
      RlsTenantContextHolder.clear();
    }
  }

  @Test
  @DisplayName("有效租户上下文写入会话变量，并在 TRACE 下记录净化后的租户")
  void applyWithValidTenant_writesSessionVariableAndLogsSanitizedTenant() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("SELECT set_config(?, ?, true)")).thenReturn(statement);
    Logger logger = (Logger) LoggerFactory.getLogger(RlsTenantSessionSupport.class);
    Level originalLevel = logger.getLevel();
    RlsTenantContextHolder.set("tenant-01");
    try {
      logger.setLevel(Level.TRACE);
      RlsTenantSessionSupport.applyIfPresent(dataSource);
      verify(statement).setString(1, RlsTenantSessionSupport.SESSION_VAR_NAME);
      verify(statement).setString(2, "tenant-01");
      verify(statement).execute();
    } finally {
      logger.setLevel(originalLevel);
      RlsTenantContextHolder.clear();
    }
  }

  @Test
  @DisplayName("租户标识白名单接受字母数字、连字符与下划线,长度上限内均通过")
  void tenantIdPattern_acceptsAsciiAlnumDashUnderscore() {
    // 准备 / 执行 / 断言
    assertThat(RlsTenantSessionSupport.TENANT_ID_PATTERN.matcher("ta").matches())
        .isTrue();
    assertThat(RlsTenantSessionSupport.TENANT_ID_PATTERN.matcher("Tenant-01_b").matches())
        .isTrue();
    assertThat(RlsTenantSessionSupport.TENANT_ID_PATTERN.matcher("a".repeat(64)).matches())
        .isTrue();
  }

  @Test
  @DisplayName("注入形态、控制字符与超长标识一律拒绝")
  void tenantIdPattern_rejectsInjectionShapes() {
    // 单引号 / 反斜杠 / 注释 / 分号 / 控制字符 / Unicode escape 全拒
    assertThat(RlsTenantSessionSupport.TENANT_ID_PATTERN.matcher("ta'; DROP--").matches())
        .isFalse();
    assertThat(RlsTenantSessionSupport.TENANT_ID_PATTERN.matcher("ta\\u0027").matches())
        .isFalse();
    assertThat(RlsTenantSessionSupport.TENANT_ID_PATTERN.matcher("ta;b").matches())
        .isFalse();
    assertThat(RlsTenantSessionSupport.TENANT_ID_PATTERN.matcher("ta b").matches())
        .isFalse();
    assertThat(RlsTenantSessionSupport.TENANT_ID_PATTERN.matcher("\n").matches())
        .isFalse();
    assertThat(RlsTenantSessionSupport.TENANT_ID_PATTERN.matcher("").matches()).isFalse();
    // 65 字符超长拒
    assertThat(RlsTenantSessionSupport.TENANT_ID_PATTERN.matcher("a".repeat(65)).matches())
        .isFalse();
  }
}
