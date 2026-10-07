package io.github.pinpols.batch.common.persistence.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.logging.StructuredLogField;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Invocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("审计字段拦截器:验证新增补全、更新强制刷新、已有值保留、参数多种形态遍历与查询语句跳过")
class AuditFieldsInterceptorTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-05-20T10:00:00Z");
  private final Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
  private final AuditFieldsInterceptor interceptor = new AuditFieldsInterceptor(fixedClock);

  @BeforeEach
  void seedMdc() {
    MDC.put(StructuredLogField.OPERATOR_ID, "u-alice");
    MDC.put(StructuredLogField.TENANT_ID, "tenant-x");
  }

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  @DisplayName("新增且审计字段为空时,创建时间、更新时间、创建人、更新人与租户均被补全")
  void shouldFillAllAuditFields_whenInsertAndFieldsNull() throws Throwable {
    SampleEntity entity = new SampleEntity();
    runIntercept(entity, SqlCommandType.INSERT);
    assertThat(entity.getCreatedAt()).isEqualTo(FIXED_NOW);
    assertThat(entity.getUpdatedAt()).isEqualTo(FIXED_NOW);
    assertThat(entity.getCreatedBy()).isEqualTo("u-alice");
    assertThat(entity.getUpdatedBy()).isEqualTo("u-alice");
    assertThat(entity.getTenantId()).isEqualTo("tenant-x");
  }

  @Test
  @DisplayName("新增时已有创建时间、创建人与租户保持不变,仅更新相关字段被补全")
  void shouldKeepExistingValues_whenInsertWithPresetFields() throws Throwable {
    SampleEntity entity = new SampleEntity();
    Instant earlier = Instant.parse("2020-01-01T00:00:00Z");
    entity.setCreatedAt(earlier);
    entity.setCreatedBy("manual");
    entity.setTenantId("other-tenant");
    runIntercept(entity, SqlCommandType.INSERT);
    assertThat(entity.getCreatedAt()).isEqualTo(earlier);
    assertThat(entity.getCreatedBy()).isEqualTo("manual");
    assertThat(entity.getTenantId()).isEqualTo("other-tenant");
    // updated 仍按 null 填
    assertThat(entity.getUpdatedAt()).isEqualTo(FIXED_NOW);
  }

  @Test
  @DisplayName("更新时强制刷新更新时间与更新人,创建时间与创建人保持原值")
  void shouldRefreshUpdatedFields_whenUpdate() throws Throwable {
    SampleEntity entity = new SampleEntity();
    entity.setUpdatedAt(Instant.parse("2020-01-01T00:00:00Z"));
    entity.setUpdatedBy("stale");
    entity.setCreatedAt(Instant.parse("2019-01-01T00:00:00Z"));
    entity.setCreatedBy("origin");
    runIntercept(entity, SqlCommandType.UPDATE);
    assertThat(entity.getUpdatedAt()).isEqualTo(FIXED_NOW);
    assertThat(entity.getUpdatedBy()).isEqualTo("u-alice");
    // createdAt/createdBy 不被覆盖
    assertThat(entity.getCreatedAt()).isEqualTo(Instant.parse("2019-01-01T00:00:00Z"));
    assertThat(entity.getCreatedBy()).isEqualTo("origin");
  }

  @Test
  @DisplayName("缺少调用方身份上下文时时间仍补全,人员与租户字段保持为空")
  void shouldLeaveOperatorFieldsNull_whenOperatorMissing() throws Throwable {
    MDC.clear();
    SampleEntity entity = new SampleEntity();
    runIntercept(entity, SqlCommandType.INSERT);
    assertThat(entity.getCreatedAt()).isEqualTo(FIXED_NOW);
    assertThat(entity.getCreatedBy()).isNull();
    assertThat(entity.getTenantId()).isNull();
  }

  @Test
  @DisplayName("参数为集合时,集合中每个实体都被补全审计信息")
  void shouldFillEveryEntity_whenParameterIsList() throws Throwable {
    SampleEntity e1 = new SampleEntity();
    SampleEntity e2 = new SampleEntity();
    runIntercept(List.of(e1, e2), SqlCommandType.INSERT);
    assertThat(e1.getCreatedAt()).isEqualTo(FIXED_NOW);
    assertThat(e2.getCreatedAt()).isEqualTo(FIXED_NOW);
  }

  @Test
  @DisplayName("参数为映射时,映射中的实体同样被补全,非实体取值被跳过")
  void shouldFillEntityInsideMap_whenParameterIsMap() throws Throwable {
    SampleEntity entity = new SampleEntity();
    Map<String, Object> param = Map.of("entity", entity, "extraKey", "noise");
    runIntercept(param, SqlCommandType.INSERT);
    assertThat(entity.getCreatedAt()).isEqualTo(FIXED_NOW);
  }

  @Test
  @DisplayName("查询语句不触发拦截,实体审计字段保持为空")
  void shouldSkipInterception_whenStatementIsQuery() throws Throwable {
    SampleEntity entity = new SampleEntity();
    runIntercept(entity, SqlCommandType.SELECT);
    assertThat(entity.getCreatedAt()).isNull();
  }

  @Test
  @DisplayName("实体不含审计字段时原样放行,业务字段不受影响")
  void shouldKeepEntityUntouched_whenEntityHasNoAuditFields() throws Throwable {
    NoAuditFields entity = new NoAuditFields();
    entity.setId(1L);
    runIntercept(entity, SqlCommandType.INSERT);
    assertThat(entity.getId()).isEqualTo(1L);
  }

  private void runIntercept(Object param, SqlCommandType type) throws Throwable {
    MappedStatement ms = mock(MappedStatement.class);
    when(ms.getSqlCommandType()).thenReturn(type);
    Executor executor = mock(Executor.class);
    Invocation invocation = new Invocation(
        executor,
        Executor.class.getMethod("update", MappedStatement.class, Object.class),
        new Object[] {ms, param});
    when(executor.update(ms, param)).thenReturn(1);
    interceptor.intercept(invocation);
  }

  @Data
  static class SampleEntity {
    private Long id;
    private String tenantId;
    private String createdBy;
    private String updatedBy;
    private Instant createdAt;
    private Instant updatedAt;
  }

  @Data
  static class NoAuditFields {
    private Long id;
    private String name;
  }
}
