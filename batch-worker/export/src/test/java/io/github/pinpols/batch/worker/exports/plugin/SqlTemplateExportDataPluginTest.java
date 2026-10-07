package io.github.pinpols.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("模板导出数据插件单测:分页语句在首页与后续页对游标谓词的处理语义")
class SqlTemplateExportDataPluginTest {

  @Test
  @DisplayName("首页分页语句不含游标条件,但保留排序与条数上限")
  void shouldOmitCursorPredicate_whenBuildingFirstPageSql() {
    String sql = SqlTemplateExportDataPlugin.buildPagedSql(
        "select id, batch_no from biz.settlement_detail", "id", false, 1, 1);

    assertThat(sql)
        .doesNotContain(":__cursor")
        .doesNotContain("WHERE base.\"id\" >")
        .contains("ORDER BY base.\"id\" ASC")
        .contains("LIMIT :__limit");
  }

  @Test
  @DisplayName("首页之后的分页语句追加游标比较条件,并保留排序与上限")
  void shouldAddCursorPredicate_whenBuildingSubsequentPageSql() {
    String sql = SqlTemplateExportDataPlugin.buildPagedSql(
        "select id, batch_no from biz.settlement_detail", "id", true, 1, 1);

    assertThat(sql)
        .contains("WHERE base.\"id\" > :__cursor")
        .contains("ORDER BY base.\"id\" ASC")
        .contains("LIMIT :__limit");
  }
}
