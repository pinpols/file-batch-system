package com.example.batch.worker.atomic.storedproc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import com.example.batch.worker.atomic.storedproc.StoredProcTaskExecutor.StoredProcValidationException;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;

/**
 * SPI hardening 单测(mockito,无 docker):覆盖 P-1 大小写、dataSourceBean 白名单、maxRefCursorRows 截断。 DB
 * 相关位(search_path / has_function_privilege / prokind)只能在 testcontainers IT 真测,见 {@code
 * StoredProcHardeningIntegrationTest}(本仓库不在此运行)。
 */
class StoredProcHardeningTest {

  private StoredProcExecutorProperties props;
  private DataSource ds;
  private BeanFactory beanFactory;
  private StoredProcTaskExecutor executor;

  @BeforeEach
  void setUp() {
    props = new StoredProcExecutorProperties();
    props.setForbidOsCapableRole(false); // 单测 mock 连接,新 DB 闸由真 PG IT 覆盖
    props.setAllowSecurityDefiner(true);
    props.setEnabled(true);
    ds = mock(DataSource.class);
    beanFactory = mock(BeanFactory.class);
    executor = new StoredProcTaskExecutor(props, beanFactory, ds);
  }

  private TaskContext ctx(Map<String, Object> params) {
    return new TaskContext("t1", "job-1", "ti-1", "w-1", params, Map.of());
  }

  /** 配通 CALL 路径所需的 conn/cs mock(search_path 的 createStatement 也要返回 Statement)。 */
  private CallableStatement wireConnection() throws Exception {
    Connection conn = mock(Connection.class);
    CallableStatement cs = mock(CallableStatement.class);
    Statement st = mock(Statement.class);
    when(ds.getConnection()).thenReturn(conn);
    when(conn.getAutoCommit()).thenReturn(true);
    when(conn.prepareCall(anyString())).thenReturn(cs);
    lenient().when(conn.createStatement()).thenReturn(st);
    return cs;
  }

  // ─── (a) P-1 大小写:过程名变体经小写化后命中白名单 ────────────────────────────────

  @Test
  void procNameCaseVariantMatchesWhitelistAfterLowercasing() throws Exception {
    props.setProcedureWhitelist(Set.of("batch.refresh_metrics"));
    wireConnection();

    // 调用方用大写变体,PG identifier 折叠小写后等价 → 应通过 validation 并执行
    TaskResult r = executor.execute(ctx(Map.of("procedureName", "BATCH.Refresh_Metrics")));

    assertThat(r.success()).isTrue();
  }

  @Test
  void whitelistEntryCaseAlsoNormalized() throws Exception {
    // 白名单本身写成混合大小写也应匹配小写过程名
    props.setProcedureWhitelist(Set.of("Batch.Refresh_Metrics"));
    wireConnection();

    TaskResult r = executor.execute(ctx(Map.of("procedureName", "batch.refresh_metrics")));

    assertThat(r.success()).isTrue();
  }

  // ─── (b) dataSourceBean 不在白名单 → 拒绝 ──────────────────────────────────────

  @Test
  void rejectsDataSourceBeanNotInAllowlist() {
    // allowedDataSourceBeans 默认空 + 配置默认为 null → 任意覆盖都拒
    TaskResult r =
        executor.execute(ctx(Map.of("procedureName", "batch.p", "dataSourceBean", "rogueDs")));

    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("dataSourceBean not allowed");
  }

  @Test
  void resolveDataSourceBeanHelperContract() {
    // null requested → 返回 configured
    assertThat(StoredProcTaskExecutor.resolveDataSourceBean(null, "mainDs", Set.of()))
        .isEqualTo("mainDs");
    // requested == configured → 放行
    assertThat(StoredProcTaskExecutor.resolveDataSourceBean("mainDs", "mainDs", Set.of()))
        .isEqualTo("mainDs");
    // requested 在白名单 → 放行
    assertThat(
            StoredProcTaskExecutor.resolveDataSourceBean(
                "reportingDs", "mainDs", Set.of("reportingDs")))
        .isEqualTo("reportingDs");
    // 不同且不在白名单 → 抛
    assertThatThrownBy(
            () -> StoredProcTaskExecutor.resolveDataSourceBean("rogueDs", "mainDs", Set.of()))
        .isInstanceOf(StoredProcValidationException.class)
        .hasMessageContaining("dataSourceBean not allowed");
  }

  @Test
  void allowsDataSourceBeanWhenInAllowlist() throws Exception {
    props.setDataSourceBeanName("mainDs");
    props.setAllowedDataSourceBeans(Set.of("reportingDs"));
    Connection conn = mock(Connection.class);
    CallableStatement cs = mock(CallableStatement.class);
    Statement st = mock(Statement.class);
    DataSource reporting = mock(DataSource.class);
    when(beanFactory.getBean("reportingDs", DataSource.class)).thenReturn(reporting);
    when(reporting.getConnection()).thenReturn(conn);
    when(conn.getAutoCommit()).thenReturn(true);
    when(conn.prepareCall(anyString())).thenReturn(cs);
    lenient().when(conn.createStatement()).thenReturn(st);

    TaskResult r =
        executor.execute(ctx(Map.of("procedureName", "batch.p", "dataSourceBean", "reportingDs")));

    assertThat(r.success()).isTrue();
  }

  // ─── (c) maxRefCursorRows 截断 ────────────────────────────────────────────────

  @Test
  void refCursorTruncatesBeyondCap() throws Exception {
    props.setMaxRefCursorRows(3);
    CallableStatement cs = wireConnection();

    // mock REFCURSOR ResultSet:metadata 单列,next() 返回比 cap 更多的行
    ResultSet cursor = mock(ResultSet.class);
    ResultSetMetaData md = mock(ResultSetMetaData.class);
    when(cs.getObject(1)).thenReturn(cursor);
    when(cursor.getMetaData()).thenReturn(md);
    when(md.getColumnCount()).thenReturn(1);
    when(md.getColumnLabel(1)).thenReturn("c");
    // 始终有下一行(无穷),验证 cap 必须主动 break,否则会一直读
    when(cursor.next()).thenReturn(true);
    when(cursor.getObject(anyInt())).thenReturn("v");

    TaskResult r =
        executor.execute(
            ctx(Map.of("procedureName", "batch.p", "outParams", List.of("REF_CURSOR"))));

    assertThat(r.success()).isTrue();
    assertThat(r.output().get("truncated")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    List<List<Map<String, Object>>> resultSets =
        (List<List<Map<String, Object>>>) r.output().get("resultSets");
    assertThat(resultSets).hasSize(1);
    assertThat(resultSets.get(0)).hasSize(3); // 读到 cap 即停
    @SuppressWarnings("unchecked")
    Map<String, Object> outValues = (Map<String, Object>) r.output().get("outValues");
    assertThat((String) outValues.get("p1")).contains("truncated");
  }

  @Test
  void refCursorNotTruncatedWhenUnderCap() throws Exception {
    props.setMaxRefCursorRows(10);
    CallableStatement cs = wireConnection();

    ResultSet cursor = mock(ResultSet.class);
    ResultSetMetaData md = mock(ResultSetMetaData.class);
    when(cs.getObject(1)).thenReturn(cursor);
    when(cursor.getMetaData()).thenReturn(md);
    when(md.getColumnCount()).thenReturn(1);
    when(md.getColumnLabel(1)).thenReturn("c");
    when(cursor.next()).thenReturn(true, true, false); // 2 行
    when(cursor.getObject(anyInt())).thenReturn("v");

    TaskResult r =
        executor.execute(
            ctx(Map.of("procedureName", "batch.p", "outParams", List.of("REF_CURSOR"))));

    assertThat(r.success()).isTrue();
    assertThat(r.output().get("truncated")).isEqualTo(false);
    @SuppressWarnings("unchecked")
    List<List<Map<String, Object>>> resultSets =
        (List<List<Map<String, Object>>>) r.output().get("resultSets");
    assertThat(resultSets.get(0)).hasSize(2);
  }
}
