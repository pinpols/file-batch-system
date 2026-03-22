package com.example.batch.common.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JdbcMappedSqlValidatorTest {

    @Test
    void shouldNormalizeValidIdentifierToLowerCase() {
        assertThat(JdbcMappedSqlValidator.requireIdentifier("My_Table", "col")).isEqualTo("my_table");
    }

    @Test
    void shouldRejectInvalidIdentifier() {
        assertThatThrownBy(() -> JdbcMappedSqlValidator.requireIdentifier("bad-name", "col"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid characters");
    }

    @Test
    void shouldRejectBlankIdentifier() {
        assertThatThrownBy(() -> JdbcMappedSqlValidator.requireIdentifier("  ", "col"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldEnforceSchemaAllowlist() {
        assertThatThrownBy(() -> JdbcMappedSqlValidator.requireInAllowlist("other", Set.of("biz")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowlisted");
    }

    @Test
    void shouldQuotePgIdentifier() {
        assertThat(JdbcMappedSqlValidator.quotePg("biz")).isEqualTo("\"biz\"");
    }

    @Test
    void shouldRejectEmptyAllowlist() {
        assertThatThrownBy(() -> JdbcMappedSqlValidator.requireInAllowlist("biz", List.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}
