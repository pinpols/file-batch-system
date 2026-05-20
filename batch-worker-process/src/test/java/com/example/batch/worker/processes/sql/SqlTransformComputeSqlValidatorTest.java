package com.example.batch.worker.processes.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SqlTransformComputeSqlValidatorTest {

  @Test
  void validateSelect_allowsSelectFromAllowlistedSchema() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    String sql =
        validator.validateSelect(
            "select tenant_id, account_id from biz.order_event where tenant_id = :tenantId");

    assertThat(sql).contains("biz.order_event");
  }

  @Test
  void validateSelect_rejectsDml() {
    SqlTransformComputeSqlValidator validator =
        new SqlTransformComputeSqlValidator(new SqlTransformComputeSecurityProperties());

    assertThatThrownBy(() -> validator.validateSelect("delete from biz.order_event"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("only allows SELECT");
  }

  @Test
  void validateSelect_rejectsDisallowedSchema() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertThatThrownBy(
            () ->
                validator.validateSelect(
                    "select id from pg_catalog.pg_tables where schemaname = 'biz'"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("disallowed schema");
  }

  @Test
  void validateUserCheckSelect_rejectsReadingNonStagingTables() {
    SqlTransformComputeSqlValidator validator =
        new SqlTransformComputeSqlValidator(new SqlTransformComputeSecurityProperties());

    assertThatThrownBy(
            () ->
                validator.validateUserCheckSelect(
                    "select true AS pass, 'ok' AS message from biz.order_event"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("validation SQL may only read batch.process_staging");
  }

  @Test
  void validateSelect_rejectsCtasCreateTableAsSelect() {
    SqlTransformComputeSqlValidator validator =
        new SqlTransformComputeSqlValidator(new SqlTransformComputeSecurityProperties());

    // CTAS 被 JSqlParser 解析为 CreateTable, instanceof Select 检查直接拦,但要有显式守护
    assertThatThrownBy(
            () ->
                validator.validateSelect(
                    "create table biz.foo as select tenant_id from biz.order_event"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("only allows SELECT");
  }

  @Test
  void validateSelect_rejectsSetSearchPath() {
    SqlTransformComputeSqlValidator validator =
        new SqlTransformComputeSqlValidator(new SqlTransformComputeSecurityProperties());

    assertThatThrownBy(() -> validator.validateSelect("set search_path = public, biz"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validateSelect_rejectsDblinkFunction() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertThatThrownBy(
            () ->
                validator.validateSelect(
                    "select c from dblink('host=evil', 'select 1') as t(c int)"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function 'dblink'");
  }

  @Test
  void validateSelect_rejectsPgTerminateBackend() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertThatThrownBy(
            () ->
                validator.validateSelect(
                    "select pg_terminate_backend(pid) from biz.fake where tenant_id = :tenantId"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden function 'pg_terminate_backend'");
  }

  @Test
  void validateSelect_requireLimit_rejectsUnboundedQuery() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    security.setRequireLimit(true);
    security.setMaxLimitRows(10_000L);
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertThatThrownBy(
            () ->
                validator.validateSelect(
                    "select tenant_id from biz.order_event where tenant_id = :tenantId"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("LIMIT");
  }

  @Test
  void validateSelect_requireLimit_rejectsOverMaxLimit() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    security.setRequireLimit(true);
    security.setMaxLimitRows(10_000L);
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    assertThatThrownBy(
            () ->
                validator.validateSelect(
                    "select tenant_id from biz.order_event"
                        + " where tenant_id = :tenantId limit 50000"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds max");
  }

  @Test
  void validateSelect_requireLimit_acceptsLimitWithinBound() {
    SqlTransformComputeSecurityProperties security = new SqlTransformComputeSecurityProperties();
    security.setAllowedSchemas(List.of("biz"));
    security.setRequireLimit(true);
    security.setMaxLimitRows(10_000L);
    SqlTransformComputeSqlValidator validator = new SqlTransformComputeSqlValidator(security);

    String sql =
        validator.validateSelect(
            "select tenant_id from biz.order_event where tenant_id = :tenantId limit 5000");

    assertThat(sql).contains("limit 5000");
  }

  @Test
  void validateUserCheckSelect_allowsReadingProcessStaging() {
    SqlTransformComputeSqlValidator validator =
        new SqlTransformComputeSqlValidator(new SqlTransformComputeSecurityProperties());

    String sql =
        validator.validateUserCheckSelect(
            "select bool_and(tenant_id = :tenantId) AS pass from batch.process_staging"
                + " where batch_key = :batchKey");

    assertThat(sql).contains("batch.process_staging");
  }
}
