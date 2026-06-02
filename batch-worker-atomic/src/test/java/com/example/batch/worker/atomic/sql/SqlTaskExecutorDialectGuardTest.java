package com.example.batch.worker.atomic.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;

/**
 * K2:{@link SqlTaskExecutor#requireNonOsCapableRole(Connection)} 跨方言行为。
 *
 * <ul>
 *   <li>PostgreSQL → 走 {@code pg_roles} 查询(已被 IT 覆盖)
 *   <li>非 PostgreSQL → log.warn + 直接返回,不抛异常(避免假阴性)
 * </ul>
 */
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
  void shouldSkipSilently_whenDatabaseIsMySql() throws SQLException {
    Connection conn = mock(Connection.class);
    DatabaseMetaData md = mock(DatabaseMetaData.class);
    when(conn.getMetaData()).thenReturn(md);
    when(md.getDatabaseProductName()).thenReturn("MySQL");

    assertThatCode(() -> executor.requireNonOsCapableRole(conn)).doesNotThrowAnyException();
  }

  @Test
  void shouldSkipSilently_whenDatabaseIsOracle() throws SQLException {
    Connection conn = mock(Connection.class);
    DatabaseMetaData md = mock(DatabaseMetaData.class);
    when(conn.getMetaData()).thenReturn(md);
    when(md.getDatabaseProductName()).thenReturn("Oracle");

    assertThatCode(() -> executor.requireNonOsCapableRole(conn)).doesNotThrowAnyException();
  }

  @Test
  void shouldSkipSilently_whenDatabaseProductNameNull() throws SQLException {
    Connection conn = mock(Connection.class);
    DatabaseMetaData md = mock(DatabaseMetaData.class);
    when(conn.getMetaData()).thenReturn(md);
    when(md.getDatabaseProductName()).thenReturn(null);

    assertThatCode(() -> executor.requireNonOsCapableRole(conn)).doesNotThrowAnyException();
  }

  @Test
  void shouldPropagateSqlValidationException_whenMetadataThrows() throws SQLException {
    Connection conn = mock(Connection.class);
    when(conn.getMetaData()).thenThrow(new SQLException("conn closed"));

    assertThatThrownBy(() -> executor.requireNonOsCapableRole(conn))
        .isInstanceOf(SqlTaskExecutor.SqlValidationException.class)
        .hasMessageContaining("OS-capable role check failed");
  }

  @Test
  void productPropertiesShouldDocumentPostgresOnlyInJavadoc() {
    // 行为测不到 javadoc;仅留一个 sentinel 防回归字段重命名(下游 yaml key 不变)
    assertThat(props.isForbidOsCapableRole()).isTrue(); // 默认 true 不变
  }
}
