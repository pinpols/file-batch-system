package io.github.pinpols.batch.worker.atomic.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;

/**
 * K2:{@link SqlTaskExecutor#requireNonOsCapableRole(Connection)} 跨方言行为。
 *
 * <ul>
 *   <li>PostgreSQL → 走 {@code pg_roles} 查询(已被 IT 覆盖)
 *   <li>非 PostgreSQL → <b>fail-closed 抛 {@link SqlTaskExecutor.SqlValidationException}</b>:运维显式要求禁
 *       OS 角色、 而本闸在该方言下无法核验,静默放行等于安全控制 no-op,故拒绝执行(要跑非 PG 须显式 {@code forbidOsCapableRole=false})。
 * </ul>
 */
@DisplayName("数据库执行器方言守卫: 非目标方言下的默认拒绝")
class SqlTaskExecutorDialectGuardTest {

  private SqlExecutorProperties props;
  private SqlTaskExecutor executor;

  @BeforeEach
  void setUp() {
    props = new SqlExecutorProperties();
    BeanFactory beanFactory = mock(BeanFactory.class);
    DataSource ds = mock(DataSource.class);
    executor = new SqlTaskExecutor(props, beanFactory, ds);
  }

  @Test
  @DisplayName("数据库为非目标方言时应默认拒绝执行, 并说明仅支持目标方言")
  void shouldFailClosed_whenDatabaseIsMySql() throws SQLException {
    Connection conn = mock(Connection.class);
    DatabaseMetaData md = mock(DatabaseMetaData.class);
    when(conn.getMetaData()).thenReturn(md);
    when(md.getDatabaseProductName()).thenReturn("MySQL");

    assertThatThrownBy(() -> executor.requireNonOsCapableRole(conn))
        .isInstanceOf(SqlTaskExecutor.SqlValidationException.class)
        .hasMessageContaining("PostgreSQL-only")
        .hasMessageContaining("fail-closed");
  }

  @Test
  @DisplayName("另一非目标方言同样应默认拒绝, 不让安全校验静默失效")
  void shouldFailClosed_whenDatabaseIsOracle() throws SQLException {
    Connection conn = mock(Connection.class);
    DatabaseMetaData md = mock(DatabaseMetaData.class);
    when(conn.getMetaData()).thenReturn(md);
    when(md.getDatabaseProductName()).thenReturn("Oracle");

    assertThatThrownBy(() -> executor.requireNonOsCapableRole(conn))
        .isInstanceOf(SqlTaskExecutor.SqlValidationException.class)
        .hasMessageContaining("fail-closed");
  }

  @Test
  @DisplayName("无法识别数据库方言时应默认拒绝, 避免核验被跳过")
  void shouldFailClosed_whenDatabaseProductNameNull() throws SQLException {
    Connection conn = mock(Connection.class);
    DatabaseMetaData md = mock(DatabaseMetaData.class);
    when(conn.getMetaData()).thenReturn(md);
    when(md.getDatabaseProductName()).thenReturn(null);

    assertThatThrownBy(() -> executor.requireNonOsCapableRole(conn))
        .isInstanceOf(SqlTaskExecutor.SqlValidationException.class)
        .hasMessageContaining("fail-closed");
  }

  @Test
  @DisplayName("读取元数据失败时应抛出校验异常, 保留原始失败语义")
  void shouldPropagateSqlValidationException_whenMetadataThrows() throws SQLException {
    Connection conn = mock(Connection.class);
    when(conn.getMetaData()).thenThrow(new SQLException("conn closed"));

    assertThatThrownBy(() -> executor.requireNonOsCapableRole(conn))
        .isInstanceOf(SqlTaskExecutor.SqlValidationException.class)
        .hasMessageContaining("OS-capable role check failed");
  }

  @Test
  @DisplayName("禁止高权限角色的开关默认应保持开启, 防止字段重命名导致契约漂移")
  void shouldKeepForbidOsCapableRoleDefaultTrue() {
    // 行为测不到 javadoc;仅留一个 sentinel 防回归字段重命名(下游 yaml key 不变)
    assertThat(props.isForbidOsCapableRole()).isTrue(); // 默认 true 不变
  }
}
