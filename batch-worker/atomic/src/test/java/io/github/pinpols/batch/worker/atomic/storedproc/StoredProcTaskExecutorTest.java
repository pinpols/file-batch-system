package io.github.pinpols.batch.worker.atomic.storedproc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.spi.task.ResourceKind;
import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;

/** {@link StoredProcTaskExecutor} 单测 — validation / 类型映射 / mocked CALL 执行。 */
@DisplayName("存储过程执行器: 参数校验, 类型映射与调用执行")
class StoredProcTaskExecutorTest {

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

  private TaskContext ctxWithParams(Map<String, Object> params) {
    return new TaskContext("t1", "job-1", "ti-1", "w-1", params, Map.of());
  }

  // ─── Validation ──────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("参数校验: 过程名, 模式白名单与出入参类型")
  class Validation {

    @Test
    @DisplayName("缺少过程名时执行应失败, 并提示参数必填")
    void shouldReject_whenProcedureNameMissing() {
      TaskResult r = executor.execute(ctxWithParams(Map.of()));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("parameters.procedureName required");
    }

    @Test
    @DisplayName("过程名含非法字符时应拒绝执行, 阻断语句注入")
    void shouldReject_whenProcedureNameHasIllegalChars() {
      TaskResult r = executor.execute(ctxWithParams(Map.of("procedureName", "drop;table")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("must match");
    }

    @Test
    @DisplayName("参数携带敏感凭据字段时应被凭据闸门拒绝, 并返回敏感数据标识")
    void rejectsSensitiveCredentialInParameters_LaneC() {
      TaskResult r = executor.execute(
          ctxWithParams(Map.of("procedureName", "batch.foo", "client_secret", "leak")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("SENSITIVE_DATA_IN_PARAMETERS");
    }

    @Test
    @DisplayName("过程所属模式在白名单内时应直接放行, 无需逐个登记过程")
    void shouldAllow_whenSchemaAllowlisted() throws Exception {
      // schema 级放行:allowedSchemas 含 batch → batch.* 任意过程都过 validation,无需逐个列举
      props.setAllowedSchemas(Set.of("batch"));
      Connection conn = mock(Connection.class);
      CallableStatement cs = mock(CallableStatement.class);
      when(ds.getConnection()).thenReturn(conn);
      when(conn.getAutoCommit()).thenReturn(true);
      when(conn.prepareCall(anyString())).thenReturn(cs);

      TaskResult r =
          executor.execute(ctxWithParams(Map.of("procedureName", "batch.brand_new_proc")));
      assertThat(r.success()).isTrue();
    }

    @Test
    @DisplayName("过程所属模式不在白名单内时应拒绝执行, 阻止越权访问系统模式")
    void shouldReject_whenSchemaOutsideAllowedSchemas() {
      // schema 级放行挡住逃逸 schema:allowedSchemas=batch 时 pg_catalog.* 拒绝
      props.setAllowedSchemas(Set.of("batch"));
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("procedureName", "pg_catalog.evil_proc")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("not allowed");
    }

    @Test
    @DisplayName("模式限定的过程名应通过校验并进入调用路径")
    void shouldAllow_whenProcedureNameSchemaQualified() throws Exception {
      // 应通过 validation,真 SQL 执行用 mock(只测到能进 runCall)
      Connection conn = mock(Connection.class);
      CallableStatement cs = mock(CallableStatement.class);
      when(ds.getConnection()).thenReturn(conn);
      when(conn.getAutoCommit()).thenReturn(true);
      when(conn.prepareCall(anyString())).thenReturn(cs);

      TaskResult r =
          executor.execute(ctxWithParams(Map.of("procedureName", "batch.refresh_metrics")));
      assertThat(r.success()).isTrue();
    }

    @Test
    @DisplayName("入参类型不是列表时执行应失败, 提示取值类型不合法")
    void shouldReject_whenInParamsNotList() {
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("procedureName", "p", "inParams", "not-a-list")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("inParams must be a list");
    }

    @Test
    @DisplayName("出参类型不在允许清单内时应拒绝执行")
    void shouldReject_whenOutParamTypeNotAllowed() {
      TaskResult r = executor.execute(
          ctxWithParams(Map.of("procedureName", "p", "outParams", List.of("STRUCT"))));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("not in allowedOutSqlTypes");
    }

    @Test
    @DisplayName("语句超时非正数时应拒绝执行")
    void shouldReject_whenStatementTimeoutNotPositive() {
      TaskResult r = executor.execute(
          ctxWithParams(Map.of("procedureName", "p", "statementTimeoutSeconds", -1)));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("must be positive");
    }
  }

  // ─── Type mapping ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("出入参类型映射: 常用数据库类型到驱动类型的转换")
  class TypeMapping {

    @Test
    @DisplayName("常用整数, 字符串, 时间与游标等类型应映射到驱动的标准类型")
    void shouldMapCommonTypes_whenConvertingOutParamTypes() {
      assertThat(StoredProcTaskExecutor.toSqlType("BIGINT")).isEqualTo(Types.BIGINT);
      assertThat(StoredProcTaskExecutor.toSqlType("VARCHAR")).isEqualTo(Types.VARCHAR);
      assertThat(StoredProcTaskExecutor.toSqlType("TIMESTAMP")).isEqualTo(Types.TIMESTAMP);
      assertThat(StoredProcTaskExecutor.toSqlType("REF_CURSOR")).isEqualTo(Types.REF_CURSOR);
      assertThat(StoredProcTaskExecutor.toSqlType("OTHER")).isEqualTo(Types.OTHER);
    }
  }

  // ─── Capability ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("能力声明应反映配置: 任务类型为存储过程执行, 占用数据库资源且非幂等")
  void shouldExposeCapability_whenExecutorConfigured() {
    assertThat(executor.taskType()).isEqualTo("stored_proc");
    assertThat(executor.capability().resourceKinds()).containsExactly(ResourceKind.DB);
    assertThat(executor.capability().idempotent()).isFalse();
  }

  // ─── Execution (mocked) ────────────────────────────────────────────────────

  @Nested
  @DisplayName("调用执行: 占位符, 出参回读与事务处理")
  class MockedExecution {

    @Test
    @DisplayName("未限定模式的过程应先查元数据, 再按无参调用形式兜底")
    void shouldCheckUnqualifiedMetadata_whenProcedureNotSchemaQualified() throws Exception {
      Connection conn = mock(Connection.class);
      CallableStatement cs = mock(CallableStatement.class);
      PreparedStatement metadata = mock(PreparedStatement.class);
      ResultSet resultSet = mock(ResultSet.class);
      when(ds.getConnection()).thenReturn(conn);
      when(conn.getAutoCommit()).thenReturn(true);
      when(conn.prepareStatement(anyString())).thenReturn(metadata);
      when(metadata.executeQuery()).thenReturn(resultSet);
      when(resultSet.next()).thenReturn(false);
      when(conn.prepareCall(anyString())).thenReturn(cs);

      TaskResult result = executor.execute(ctxWithParams(Map.of("procedureName", "p")));

      assertThat(result.success()).isTrue();
      verify(conn).prepareCall("{call p()}");
    }

    @Test
    @DisplayName("出入参应生成对应占位符, 入参绑定取值并登记出参类型")
    void shouldBuildPlaceholders_whenInAndOutParamsPresent() throws Exception {
      Connection conn = mock(Connection.class);
      CallableStatement cs = mock(CallableStatement.class);
      when(ds.getConnection()).thenReturn(conn);
      when(conn.getAutoCommit()).thenReturn(true);
      when(conn.prepareCall(anyString())).thenReturn(cs);

      TaskResult r = executor.execute(ctxWithParams(Map.of(
          "procedureName", "batch.proc",
          "inParams", List.of(1, "x"),
          "outParams", List.of("INTEGER", "VARCHAR"))));

      assertThat(r.success()).isTrue();
      // 2 in + 2 out = 4 placeholders
      verify(conn).prepareCall("{call batch.proc(?,?,?,?)}");
      verify(cs).setObject(1, 1);
      verify(cs).setObject(2, "x");
      verify(cs).registerOutParameter(3, Types.INTEGER);
      verify(cs).registerOutParameter(4, Types.VARCHAR);
      verify(cs).execute();
    }

    @Test
    @DisplayName("调用返回后应把出参取值写入输出结果")
    void shouldReadOutValues_whenCallReturns() throws Exception {
      Connection conn = mock(Connection.class);
      CallableStatement cs = mock(CallableStatement.class);
      when(ds.getConnection()).thenReturn(conn);
      when(conn.getAutoCommit()).thenReturn(true);
      when(conn.prepareCall(anyString())).thenReturn(cs);
      when(cs.getObject(1)).thenReturn(42);
      when(cs.getObject(2)).thenReturn("hello");

      TaskResult r = executor.execute(
          ctxWithParams(Map.of("procedureName", "p", "outParams", List.of("INTEGER", "VARCHAR"))));

      assertThat(r.success()).isTrue();
      @SuppressWarnings("unchecked")
      Map<String, Object> outValues = (Map<String, Object>) r.output().get("outValues");
      assertThat(outValues).containsEntry("p1", 42).containsEntry("p2", "hello");
    }

    @Test
    @DisplayName("关闭自动提交时调用成功应显式提交")
    void shouldCommit_whenAutoCommitDisabled() throws Exception {
      Connection conn = mock(Connection.class);
      CallableStatement cs = mock(CallableStatement.class);
      when(ds.getConnection()).thenReturn(conn);
      when(conn.getAutoCommit()).thenReturn(false);
      when(conn.prepareCall(anyString())).thenReturn(cs);

      TaskResult r = executor.execute(ctxWithParams(Map.of("procedureName", "p")));

      assertThat(r.success()).isTrue();
      verify(conn).commit();
    }

    @Test
    @DisplayName("关闭自动提交时调用失败应回滚并返回失败消息")
    void shouldRollback_whenAutoCommitDisabledAndCallFails() throws Exception {
      Connection conn = mock(Connection.class);
      CallableStatement cs = mock(CallableStatement.class);
      when(ds.getConnection()).thenReturn(conn);
      when(conn.getAutoCommit()).thenReturn(false);
      when(conn.prepareCall(anyString())).thenReturn(cs);
      when(cs.execute()).thenThrow(new SQLException("call failed"));

      TaskResult r = executor.execute(ctxWithParams(Map.of("procedureName", "p")));

      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("call failed");
      verify(conn).rollback();
    }

    @Test
    @DisplayName("出参取值超过上限时应截断并标记截断位置")
    void shouldTruncateOutParam_whenValueExceedsLimit() throws Exception {
      props.setMaxOutBytesPerParam(10);
      Connection conn = mock(Connection.class);
      CallableStatement cs = mock(CallableStatement.class);
      when(ds.getConnection()).thenReturn(conn);
      when(conn.getAutoCommit()).thenReturn(true);
      when(conn.prepareCall(anyString())).thenReturn(cs);
      when(cs.getObject(1)).thenReturn("0123456789ABCDEFGH");

      TaskResult r = executor.execute(
          ctxWithParams(Map.of("procedureName", "p", "outParams", List.of("VARCHAR"))));

      @SuppressWarnings("unchecked")
      Map<String, Object> out = (Map<String, Object>) r.output().get("outValues");
      assertThat((String) out.get("p1")).startsWith("0123456789").endsWith("<truncated>");
    }
  }
}
