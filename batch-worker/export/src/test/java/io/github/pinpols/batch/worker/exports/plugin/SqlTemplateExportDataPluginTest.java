package io.github.pinpols.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlTemplateExportDataPluginTest {

  @Test
  void buildPagedSqlOmitsCursorPredicateOnFirstPage() {
    String sql =
        SqlTemplateExportDataPlugin.buildPagedSql(
            "select id, batch_no from biz.settlement_detail", "id", false, 1, 1);

    assertThat(sql).doesNotContain(":__cursor");
    assertThat(sql).doesNotContain("WHERE base.\"id\" >");
    assertThat(sql).contains("ORDER BY base.\"id\" ASC");
    assertThat(sql).contains("LIMIT :__limit");
  }

  @Test
  void buildPagedSqlAddsCursorPredicateAfterFirstPage() {
    String sql =
        SqlTemplateExportDataPlugin.buildPagedSql(
            "select id, batch_no from biz.settlement_detail", "id", true, 1, 1);

    assertThat(sql).contains("WHERE base.\"id\" > :__cursor");
    assertThat(sql).contains("ORDER BY base.\"id\" ASC");
    assertThat(sql).contains("LIMIT :__limit");
  }
}
