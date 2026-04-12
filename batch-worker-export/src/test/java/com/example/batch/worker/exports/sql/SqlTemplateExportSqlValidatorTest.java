package com.example.batch.worker.exports.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.worker.exports.config.SqlTemplateExportSecurityProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlTemplateExportSqlValidatorTest {

  private SqlTemplateExportSqlValidator validatorWithDefaults() {
    return new SqlTemplateExportSqlValidator(new SqlTemplateExportSecurityProperties());
  }

  // ── blank / null ────────────────────────────────────────────────────────────

  @Test
  void validate_throwsOnBlank() {
    assertThatThrownBy(() -> validatorWithDefaults().validate("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
  }

  @Test
  void validate_throwsOnNull() {
    assertThatThrownBy(() -> validatorWithDefaults().validate(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
  }

  // ── non-SELECT statements ────────────────────────────────────────────────────

  @Test
  void validate_throwsOnInsert() {
    assertThatThrownBy(() -> validatorWithDefaults().validate("INSERT INTO t VALUES (1)"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SELECT");
  }

  @Test
  void validate_throwsOnUpdate() {
    assertThatThrownBy(
            () ->
                validatorWithDefaults()
                    .validate("UPDATE t SET a = 1 WHERE id = :tenantId AND batch_no = :batchNo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SELECT");
  }

  @Test
  void validate_throwsOnDrop() {
    assertThatThrownBy(() -> validatorWithDefaults().validate("DROP TABLE t"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ── SELECT * ─────────────────────────────────────────────────────────────────

  @Test
  void validate_throwsOnSelectStar() {
    assertThatThrownBy(
            () ->
                validatorWithDefaults()
                    .validate(
                        "SELECT * FROM biz.t WHERE tenant_id = :tenantId AND batch_no = :batchNo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SELECT *");
  }

  @Test
  void validate_throwsOnSelectTableStar() {
    assertThatThrownBy(
            () ->
                validatorWithDefaults()
                    .validate(
                        "SELECT t.* FROM biz.t t WHERE t.tenant_id = :tenantId AND t.batch_no ="
                            + " :batchNo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SELECT *");
  }

  @Test
  void validate_throwsOnSelectStarInUnion() {
    assertThatThrownBy(
            () ->
                validatorWithDefaults()
                    .validate(
                        "SELECT id FROM biz.a WHERE tenant_id = :tenantId AND batch_no = :batchNo "
                            + "UNION ALL SELECT * FROM biz.b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SELECT *");
  }

  @Test
  void validate_allowsSelectStarWhenForbidDisabled() {
    SqlTemplateExportSecurityProperties props = new SqlTemplateExportSecurityProperties();
    props.setForbidSelectStar(false);
    props.setRequiredParams(List.of("tenantId", "batchNo"));
    SqlTemplateExportSqlValidator v = new SqlTemplateExportSqlValidator(props);

    String result =
        v.validate("SELECT * FROM biz.t WHERE tenant_id = :tenantId AND batch_no = :batchNo");
    assertThat(result).isNotBlank();
  }

  // ── schema whitelist ──────────────────────────────────────────────────────────

  @Test
  void validate_throwsOnDisallowedSchema() {
    SqlTemplateExportSecurityProperties props = new SqlTemplateExportSecurityProperties();
    props.setAllowedSchemas(List.of("biz"));
    props.setForbidSelectStar(false);
    SqlTemplateExportSqlValidator v = new SqlTemplateExportSqlValidator(props);

    assertThatThrownBy(
            () ->
                v.validate(
                    "SELECT id FROM pg_catalog.pg_tables WHERE tenant_id = :tenantId AND batch_no ="
                        + " :batchNo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pg_catalog");
  }

  @Test
  void validate_throwsOnUnqualifiedTable_whenWhitelistSet() {
    SqlTemplateExportSecurityProperties props = new SqlTemplateExportSecurityProperties();
    props.setAllowedSchemas(List.of("biz"));
    props.setForbidSelectStar(false);
    SqlTemplateExportSqlValidator v = new SqlTemplateExportSqlValidator(props);

    // Unqualified table (no schema prefix) must be rejected — prevents bypassing the whitelist
    // by referencing internal tables like batch.job_task without a schema prefix
    assertThatThrownBy(
            () ->
                v.validate(
                    "SELECT id FROM unqualified_table WHERE tenant_id = :tenantId AND batch_no ="
                        + " :batchNo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unqualified_table");
  }

  @Test
  void validate_allowsWhitelistedSchema() {
    SqlTemplateExportSecurityProperties props = new SqlTemplateExportSecurityProperties();
    props.setAllowedSchemas(List.of("biz", "ref"));
    props.setForbidSelectStar(false);
    SqlTemplateExportSqlValidator v = new SqlTemplateExportSqlValidator(props);

    String result =
        v.validate(
            "SELECT id, name FROM biz.customer_account ca "
                + "JOIN ref.currency_code cc ON cc.code = ca.currency "
                + "WHERE ca.tenant_id = :tenantId AND ca.batch_no = :batchNo");
    assertThat(result).isNotBlank();
  }

  // ── required params ──────────────────────────────────────────────────────────

  @Test
  void validate_throwsWhenTenantIdMissing() {
    assertThatThrownBy(
            () ->
                validatorWithDefaults().validate("SELECT id FROM biz.t WHERE batch_no = :batchNo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(":tenantId");
  }

  @Test
  void validate_throwsWhenBatchNoMissing() {
    assertThatThrownBy(
            () ->
                validatorWithDefaults()
                    .validate("SELECT id FROM biz.t WHERE tenant_id = :tenantId"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(":batchNo");
  }

  // ── valid SQL ────────────────────────────────────────────────────────────────

  @Test
  void validate_returnsNormalizedSqlForValidQuery() {
    String sql =
        "  SELECT id, name FROM biz.t WHERE tenant_id = :tenantId AND batch_no = :batchNo  ";
    String result = validatorWithDefaults().validate(sql);
    assertThat(result).isEqualTo(sql.trim());
  }

  @Test
  void validate_acceptsWithClause() {
    String sql =
        """
        WITH filtered AS (
          SELECT id, amount FROM biz.settlement_detail
          WHERE tenant_id = :tenantId AND batch_no = :batchNo
        )
        SELECT f.id, f.amount FROM filtered f
        ORDER BY f.id
        """
            .trim();
    String result = validatorWithDefaults().validate(sql);
    assertThat(result).isNotBlank();
  }

  @Test
  void validate_acceptsJoin() {
    String sql =
        "SELECT sb.batch_no, sd.settlement_no "
            + "FROM biz.settlement_detail sd "
            + "JOIN biz.settlement_batch sb ON sb.id = sd.batch_id "
            + "WHERE sb.tenant_id = :tenantId AND sb.batch_no = :batchNo";
    String result = validatorWithDefaults().validate(sql);
    assertThat(result).isEqualTo(sql);
  }
}
