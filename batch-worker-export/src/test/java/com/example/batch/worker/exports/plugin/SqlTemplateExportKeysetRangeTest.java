package com.example.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** buildPagedSql 的 keyset-range 重载：纯 SQL 字符串断言，不连库。 */
class SqlTemplateExportKeysetRangeTest {

  private static final String BASE_SQL = "SELECT id FROM biz.t WHERE tenant_id = :tenantId";

  @Test
  @DisplayName("active 非末片 → 区间谓词 [loN, hiN)，不退 hashtext")
  void shouldEmitRangePredicate_whenActiveNonLastPartition() {
    ExportKeysetRange range =
        new ExportKeysetRange(true, new BigDecimal("0"), new BigDecimal("25"), false, 4, 1);

    String sql = SqlTemplateExportDataPlugin.buildPagedSql(BASE_SQL, "id", false, range);

    assertThat(sql).contains("base.\"id\" >= :__loN");
    assertThat(sql).contains("base.\"id\" < :__hiN");
    assertThat(sql).doesNotContain("hashtext");
  }

  @Test
  @DisplayName("active 末片 includeUpper=true → 含上界 <=")
  void shouldEmitInclusiveUpper_whenLastPartition() {
    ExportKeysetRange range =
        new ExportKeysetRange(true, new BigDecimal("75"), new BigDecimal("100"), true, 4, 4);

    String sql = SqlTemplateExportDataPlugin.buildPagedSql(BASE_SQL, "id", false, range);

    assertThat(sql).contains("base.\"id\" <= :__hiN");
    assertThat(sql).doesNotContain("hashtext");
  }

  @Test
  @DisplayName("inactive → 退回 hashtext 分片谓词，不含区间参数")
  void shouldFallBackToHashtext_whenInactive() {
    ExportKeysetRange range = ExportKeysetRange.inactiveFor(4, 2);

    String sql = SqlTemplateExportDataPlugin.buildPagedSql(BASE_SQL, "id", false, range);

    assertThat(sql).contains("hashtext");
    assertThat(sql).doesNotContain(":__loN");
  }

  @Test
  @DisplayName("active + hasCursor → 区间谓词与游标谓词共存")
  void shouldCombineRangeAndCursor_whenActiveWithCursor() {
    ExportKeysetRange range =
        new ExportKeysetRange(true, new BigDecimal("0"), new BigDecimal("25"), false, 4, 1);

    String sql = SqlTemplateExportDataPlugin.buildPagedSql(BASE_SQL, "id", true, range);

    assertThat(sql).contains("base.\"id\" > :__cursor");
    assertThat(sql).contains("base.\"id\" < :__hiN");
  }
}
