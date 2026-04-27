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
