package io.github.pinpols.batch.common.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("映射数据源标识符校验:归一化、字符集与白名单约束")
class JdbcMappedSqlValidatorTest {

  @Test
  @DisplayName("合法标识符被归一化为小写")
  void shouldNormalizeValidIdentifierToLowerCase() {
    assertThat(JdbcMappedSqlValidator.requireIdentifier("My_Table", "col")).isEqualTo("my_table");
  }

  @Test
  @DisplayName("含非法字符的标识符被拒绝")
  void shouldRejectInvalidIdentifier() {
    assertThatThrownBy(() -> JdbcMappedSqlValidator.requireIdentifier("bad-name", "col"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid characters");
  }

  @Test
  @DisplayName("空白标识符被拒绝")
  void shouldRejectBlankIdentifier() {
    assertThatThrownBy(() -> JdbcMappedSqlValidator.requireIdentifier("  ", "col"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("模式名不在白名单内时被拒绝")
  void shouldEnforceSchemaAllowlist() {
    assertThatThrownBy(() -> JdbcMappedSqlValidator.requireInAllowlist("other", Set.of("biz")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not allowlisted");
  }

  @Test
  @DisplayName("标识符按目标数据库语法加引号")
  void shouldQuotePgIdentifier() {
    assertThat(JdbcMappedSqlValidator.quotePg("biz")).isEqualTo("\"biz\"");
  }

  @Test
  @DisplayName("白名单为空时以非法状态快速失败")
  void shouldRejectEmptyAllowlist() {
    assertThatThrownBy(() -> JdbcMappedSqlValidator.requireInAllowlist("biz", List.of()))
        .isInstanceOf(IllegalStateException.class);
  }
}
