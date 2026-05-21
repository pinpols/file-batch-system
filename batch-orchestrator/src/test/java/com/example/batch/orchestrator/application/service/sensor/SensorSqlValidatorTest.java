package com.example.batch.orchestrator.application.service.sensor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SensorSqlValidatorTest {

  private static final List<String> ALLOWED = List.of("biz");

  @Test
  void validate_simpleSelect_passes() {
    String sql = "SELECT id FROM biz.signal WHERE status = :status LIMIT 1";
    assertThat(SensorSqlValidator.validate(sql, ALLOWED)).isEqualTo(sql);
  }

  @Test
  void validate_blankSql_throws() {
    assertThatThrownBy(() -> SensorSqlValidator.validate("  ", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
  }

  @Test
  void validateUpdateThrows() {
    assertThatThrownBy(
            () -> SensorSqlValidator.validate("UPDATE biz.signal SET status='X'", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SELECT/WITH");
  }

  @Test
  void validate_selectStar_throws() {
    assertThatThrownBy(() -> SensorSqlValidator.validate("SELECT * FROM biz.signal", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbids SELECT *");
  }

  @Test
  void validate_disallowedSchema_throws() {
    assertThatThrownBy(() -> SensorSqlValidator.validate("SELECT id FROM hr.payroll", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("disallowed schema");
  }

  @Test
  void validate_unqualifiedTable_throws() {
    assertThatThrownBy(() -> SensorSqlValidator.validate("SELECT id FROM signal", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schema.table");
  }

  @Test
  void validate_garbageSql_throws() {
    assertThatThrownBy(() -> SensorSqlValidator.validate("this is not sql", ALLOWED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("parse error");
  }
}
