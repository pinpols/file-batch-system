package com.example.batch.console.domain.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.ops.entity.AtomicTaskConfigEntity;
import com.example.batch.console.domain.ops.mapper.AtomicTaskConfigMapper;
import com.example.batch.console.domain.ops.param.AtomicTaskConfigCreateParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * R3-5 — {@link ConsoleAtomicTaskConfigService} 单测。
 *
 * <p>覆盖:
 *
 * <ul>
 *   <li>合法创建 → 落库 + 回读返回 entity
 *   <li>schema 校验失败:taskType 未知 / 缺必填 / 非法 key
 *   <li>凭据字段静态拒入(SensitiveDataValidator 接入守护)
 *   <li>列表只允许已知 taskType
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ConsoleAtomicTaskConfigServiceTest {

  private static final String TENANT = "ta";

  @Mock private AtomicTaskConfigMapper mapper;

  // schemaService 是无依赖单例,直接 @Spy 真实对象(测试要的是 catalog 数据,不需要 stub)
  @Spy
  private ConsoleAtomicTaskTypeSchemaService schemaService =
      new ConsoleAtomicTaskTypeSchemaService();

  @Spy private ObjectMapper objectMapper = new ObjectMapper();

  @InjectMocks private ConsoleAtomicTaskConfigService service;

  @Test
  void shouldCreateSqlConfig_whenParametersMatchSchema() {
    // 准备
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("sql", "select 1");
    params.put("statementTimeoutSeconds", 30);
    when(mapper.insertAtomicTaskConfig(any())).thenAnswer(assignIdAndReturn(42L));
    when(mapper.selectByTenantAndId(TENANT, 42L)).thenReturn(fixtureEntity(42L, "sql", "daily"));

    // 执行
    AtomicTaskConfigEntity created = service.create(TENANT, "sql", "daily", params, "alice");

    // 断言
    assertThat(created.getId()).isEqualTo(42L);
    assertThat(created.getTaskType()).isEqualTo("sql");
    verify(mapper).insertAtomicTaskConfig(any(AtomicTaskConfigCreateParam.class));
  }

  @Test
  void shouldReject_whenTaskTypeUnknown() {
    assertThatThrownBy(
            () -> service.create(TENANT, "totally_unknown", "x", Map.of("sql", "select 1"), null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.atomic_task_config.task_type_unknown");
    verify(mapper, never()).insertAtomicTaskConfig(any());
  }

  @Test
  void shouldReject_whenRequiredParameterMissing() {
    // sql.PARAM "sql" 必填,空字符串等同缺失
    assertThatThrownBy(() -> service.create(TENANT, "sql", "missing", Map.of("sql", ""), null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.atomic_task_config.parameters_required_missing");
    verify(mapper, never()).insertAtomicTaskConfig(any());
  }

  @Test
  void shouldReject_whenParametersContainExtraneousKey() {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("sql", "select 1");
    params.put("notDeclaredInSchema", "evil"); // 非法 key
    assertThatThrownBy(() -> service.create(TENANT, "sql", "x", params, null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.atomic_task_config.parameters_unknown_key");
    verify(mapper, never()).insertAtomicTaskConfig(any());
  }

  @Test
  void shouldRejectSensitiveKeyInParameters_viaSensitiveDataValidator() {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("sql", "select 1");
    params.put("dbPassword", "leak"); // contains "password" → SensitiveDataValidator 拒入
    assertThatThrownBy(() -> service.create(TENANT, "sql", "x", params, null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.security.sensitive_in_payload");
    verify(mapper, never()).insertAtomicTaskConfig(any());
  }

  @Test
  void shouldRejectSensitiveKeyInNestedMap() {
    Map<String, Object> auth = new LinkedHashMap<>();
    auth.put("type", "basic");
    auth.put("apiKey", "leak"); // nested 凭据
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("url", "https://example.com");
    params.put("auth", auth);
    assertThatThrownBy(() -> service.create(TENANT, "http", "x", params, null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.security.sensitive_in_payload");
  }

  @Test
  void shouldListByTaskType_whenTaskTypeIsKnown() {
    when(mapper.selectByTenantAndTaskType(TENANT, "sql"))
        .thenReturn(List.of(fixtureEntity(1L, "sql", "a"), fixtureEntity(2L, "sql", "b")));

    List<AtomicTaskConfigEntity> rows = service.listByTaskType(TENANT, "sql");

    assertThat(rows).hasSize(2);
    verify(mapper).selectByTenantAndTaskType(TENANT, "sql");
  }

  @Test
  void shouldRejectListing_whenTaskTypeUnknown() {
    assertThatThrownBy(() -> service.listByTaskType(TENANT, "unknown"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.atomic_task_config.task_type_unknown");
    verify(mapper, never()).selectByTenantAndTaskType(anyString(), anyString());
  }

  @Test
  void shouldRejectBlankTaskType() {
    assertThatThrownBy(() -> service.create(TENANT, " ", "x", Map.of(), null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.atomic_task_config.task_type_required");
  }

  @Test
  void shouldRejectBlankName() {
    assertThatThrownBy(() -> service.create(TENANT, "sql", "  ", Map.of("sql", "select 1"), null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.atomic_task_config.name_required");
  }

  // ---------- helpers ----------

  private static AtomicTaskConfigEntity fixtureEntity(long id, String taskType, String name) {
    AtomicTaskConfigEntity e = new AtomicTaskConfigEntity();
    e.setId(id);
    e.setTenantId(TENANT);
    e.setTaskType(taskType);
    e.setName(name);
    e.setParameters("{}");
    return e;
  }

  /** 模拟 mapper.insertAtomicTaskConfig 的 useGeneratedKeys 副作用:回写 id 到 param。 */
  private static org.mockito.stubbing.Answer<Integer> assignIdAndReturn(long id) {
    return (InvocationOnMock inv) -> {
      AtomicTaskConfigCreateParam param = inv.getArgument(0);
      param.setId(id);
      return 1;
    };
  }
}
