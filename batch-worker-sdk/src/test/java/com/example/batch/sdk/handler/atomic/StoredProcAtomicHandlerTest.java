package com.example.batch.sdk.handler.atomic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StoredProcAtomicHandler} 单测 — 经基类 {@code execute} 验证三道闸 + CALL 行为。
 *
 * <p>注:batch-worker-sdk 仅依赖 {@code mockito-core}(无 {@code mockito-junit-jupiter}),故沿用本模块既有 {@code
 * Mockito.mock(...)} 工厂式 mock 风格(参考 {@code TaskDispatcherTest}),不用 {@code @ExtendWith}。
 */
@DisplayName("StoredProcAtomicHandler — 三道闸 + CALL 行为")
class StoredProcAtomicHandlerTest {

  private DataSource dataSource;
  private Connection connection;
  private CallableStatement callableStatement;

  // 角色闸(闸 1)与 SECURITY DEFINER(闸 3)的 query mock
  private PreparedStatement rolePs;
  private ResultSet roleRs;
  private PreparedStatement secdefPs;
  private ResultSet secdefRs;

  @BeforeEach
  void setUp() throws Exception {
    dataSource = mock(DataSource.class);
    connection = mock(Connection.class);
    callableStatement = mock(CallableStatement.class);
    rolePs = mock(PreparedStatement.class);
    roleRs = mock(ResultSet.class);
    secdefPs = mock(PreparedStatement.class);
    secdefRs = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
  }

  /** 把闸 1 角色查询接到 rolePs/roleRs,闸 3 secdef 查询接到 secdefPs/secdefRs(按 SQL 文本路由)。 */
  private void stubGateQueries() throws Exception {
    when(connection.prepareStatement(anyString()))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              return sql.contains("pg_execute_server_program") ? rolePs : secdefPs;
            });
  }

  private void stubRoleGate(boolean osCapable) throws Exception {
    when(rolePs.executeQuery()).thenReturn(roleRs);
    when(roleRs.next()).thenReturn(true);
    when(roleRs.getBoolean(1)).thenReturn(osCapable);
  }

  private void stubSecDefGate(boolean secdef) throws Exception {
    when(secdefPs.executeQuery()).thenReturn(secdefRs);
    when(secdefRs.next()).thenReturn(true);
    when(secdefRs.getBoolean(1)).thenReturn(secdef);
  }

  private void stubCall() throws Exception {
    when(connection.prepareCall(anyString())).thenReturn(callableStatement);
  }

  private static SdkTaskContext ctx(Map<String, Object> params) {
    return new SdkTaskContext("t1", "job1", "ti1", 1L, "w1", params, Map.of());
  }

  private static StoredProcAtomicHandler defaultsHandler(DataSource ds) {
    return new StoredProcAtomicHandler(StoredProcAtomicConfig.defaults("stored_proc"), ds);
  }

  // ─── 正常路径 ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("正常 CALL + OUT 读取 → outValues + success")
  void shouldReturnOutValues_whenCallSucceeds() throws Exception {
    // arrange
    stubGateQueries();
    stubRoleGate(false);
    stubSecDefGate(false);
    stubCall();
    when(callableStatement.getObject(1)).thenReturn(42); // p1 (out at pos 1, no in params)

    // act
    SdkTaskResult r =
        defaultsHandler(dataSource)
            .execute(
                ctx(Map.of("procedureName", "batch.refresh", "outParams", List.of("INTEGER"))));

    // assert
    assertThat(r.success()).isTrue();
    assertThat(r.output()).containsEntry("procedureName", "batch.refresh");
    @SuppressWarnings("unchecked")
    Map<String, Object> outValues = (Map<String, Object>) r.output().get("outValues");
    assertThat(outValues).containsEntry("p1", 42);
    verify(callableStatement).registerOutParameter(1, Types.INTEGER);
    verify(callableStatement).setQueryTimeout(60);
  }

  // ─── 参数校验 ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("缺 procedureName → fail")
  void shouldFail_whenProcedureNameMissing() {
    SdkTaskResult r = defaultsHandler(dataSource).execute(ctx(Map.of("outParams", List.of())));

    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("procedureName required");
  }

  @Test
  @DisplayName("非法 procedureName 格式 → fail")
  void shouldFail_whenProcedureNameIllegal() {
    SdkTaskResult r =
        defaultsHandler(dataSource)
            .execute(ctx(Map.of("procedureName", "batch.refresh; DROP TABLE x")));

    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("procedureName must match");
  }

  // ─── 闸 2:allowedSchemas ─────────────────────────────────────────────────────

  @Test
  @DisplayName("闸 2:schema 不在白名单 → SecurityException → fail")
  void shouldFail_whenSchemaNotAllowed() {
    StoredProcAtomicConfig cfg =
        new StoredProcAtomicConfig("stored_proc", Set.of("batch"), false, true, 60);

    SdkTaskResult r =
        new StoredProcAtomicHandler(cfg, dataSource)
            .execute(ctx(Map.of("procedureName", "public.foo")));

    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("schema not allowed");
  }

  @Test
  @DisplayName("闸 2:schema 在白名单 → 放行(走到 CALL)")
  void shouldPass_whenSchemaAllowed() throws Exception {
    stubGateQueries();
    stubRoleGate(false);
    stubSecDefGate(false);
    stubCall();
    StoredProcAtomicConfig cfg =
        new StoredProcAtomicConfig("stored_proc", Set.of("batch"), false, true, 60);

    SdkTaskResult r =
        new StoredProcAtomicHandler(cfg, dataSource)
            .execute(ctx(Map.of("procedureName", "batch.foo")));

    assertThat(r.success()).isTrue();
    verify(connection).prepareCall(anyString());
  }

  // ─── 闸 1:forbidOsCapableRole ────────────────────────────────────────────────

  @Test
  @DisplayName("闸 1:OS 能力角色命中 → SecurityException → fail")
  void shouldFail_whenOsCapableRole() throws Exception {
    stubGateQueries();
    stubRoleGate(true);

    SdkTaskResult r =
        defaultsHandler(dataSource).execute(ctx(Map.of("procedureName", "batch.refresh")));

    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("OS-capable DB role");
    verify(connection, never()).prepareCall(anyString());
  }

  // ─── 闸 3:allowSecurityDefiner ───────────────────────────────────────────────

  @Test
  @DisplayName("闸 3:prosecdef=true 且 allowSecurityDefiner=false → fail")
  void shouldFail_whenSecurityDefiner() throws Exception {
    stubGateQueries();
    stubRoleGate(false);
    stubSecDefGate(true);

    SdkTaskResult r =
        defaultsHandler(dataSource).execute(ctx(Map.of("procedureName", "batch.refresh")));

    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("SECURITY DEFINER");
    verify(connection, never()).prepareCall(anyString());
  }

  @Test
  @DisplayName("闸 3:allowSecurityDefiner=true → 跳过 secdef 检查")
  void shouldSkipSecDefCheck_whenAllowSecurityDefiner() throws Exception {
    stubGateQueries();
    stubRoleGate(false);
    stubCall();
    StoredProcAtomicConfig cfg =
        new StoredProcAtomicConfig("stored_proc", Set.of(), true, true, 60);

    SdkTaskResult r =
        new StoredProcAtomicHandler(cfg, dataSource)
            .execute(ctx(Map.of("procedureName", "batch.refresh")));

    assertThat(r.success()).isTrue();
    verify(secdefPs, never()).executeQuery();
  }

  // ─── toSqlType / buildCall ───────────────────────────────────────────────────

  @Test
  @DisplayName("toSqlType 不支持类型 → fail")
  void shouldFail_whenUnsupportedOutType() throws Exception {
    stubGateQueries();
    stubRoleGate(false);
    stubSecDefGate(false);
    stubCall();

    SdkTaskResult r =
        defaultsHandler(dataSource)
            .execute(ctx(Map.of("procedureName", "batch.refresh", "outParams", List.of("JSONB"))));

    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("unsupported SQL type");
  }

  @Test
  @DisplayName("buildCall 占位符拼接正确")
  void shouldBuildCallPlaceholders() {
    assertThat(StoredProcAtomicHandler.buildCall("batch.foo", 0)).isEqualTo("{call batch.foo()}");
    assertThat(StoredProcAtomicHandler.buildCall("batch.foo", 3))
        .isEqualTo("{call batch.foo(?,?,?)}");
  }
}
