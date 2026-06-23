package io.github.pinpols.batch.common.persistence.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;

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
  void insertFillsAllAuditFieldsWhenNull() throws Throwable {
    SampleEntity entity = new SampleEntity();
    runIntercept(entity, SqlCommandType.INSERT);
    assertThat(entity.getCreatedAt()).isEqualTo(FIXED_NOW);
    assertThat(entity.getUpdatedAt()).isEqualTo(FIXED_NOW);
    assertThat(entity.getCreatedBy()).isEqualTo("u-alice");
    assertThat(entity.getUpdatedBy()).isEqualTo("u-alice");
    assertThat(entity.getTenantId()).isEqualTo("tenant-x");
  }

  @Test
  void insertRespectsExistingValues() throws Throwable {
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
  void updateForceRefreshesUpdatedFields() throws Throwable {
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
  void mdcMissingLeavesFieldsAsNull() throws Throwable {
    MDC.clear();
    SampleEntity entity = new SampleEntity();
    runIntercept(entity, SqlCommandType.INSERT);
    assertThat(entity.getCreatedAt()).isEqualTo(FIXED_NOW);
    assertThat(entity.getCreatedBy()).isNull();
    assertThat(entity.getTenantId()).isNull();
  }

  @Test
  void batchInsertList() throws Throwable {
    SampleEntity e1 = new SampleEntity();
    SampleEntity e2 = new SampleEntity();
    runIntercept(List.of(e1, e2), SqlCommandType.INSERT);
    assertThat(e1.getCreatedAt()).isEqualTo(FIXED_NOW);
    assertThat(e2.getCreatedAt()).isEqualTo(FIXED_NOW);
  }

  @Test
  void paramMapEachValueProcessed() throws Throwable {
    SampleEntity entity = new SampleEntity();
    Map<String, Object> param = Map.of("entity", entity, "extraKey", "noise");
    runIntercept(param, SqlCommandType.INSERT);
    assertThat(entity.getCreatedAt()).isEqualTo(FIXED_NOW);
  }

  @Test
  void selectIsIgnored() throws Throwable {
    SampleEntity entity = new SampleEntity();
    runIntercept(entity, SqlCommandType.SELECT);
    assertThat(entity.getCreatedAt()).isNull();
  }

  @Test
  void entityWithoutAuditFieldsNoOp() throws Throwable {
    NoAuditFields entity = new NoAuditFields();
    entity.setId(1L);
    runIntercept(entity, SqlCommandType.INSERT);
    assertThat(entity.getId()).isEqualTo(1L);
  }

  private void runIntercept(Object param, SqlCommandType type) throws Throwable {
    MappedStatement ms = Mockito.mock(MappedStatement.class);
    Mockito.when(ms.getSqlCommandType()).thenReturn(type);
    Executor executor = Mockito.mock(Executor.class);
    Invocation invocation =
        new Invocation(
            executor,
            Executor.class.getMethod("update", MappedStatement.class, Object.class),
            new Object[] {ms, param});
    Mockito.when(executor.update(ms, param)).thenReturn(1);
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
