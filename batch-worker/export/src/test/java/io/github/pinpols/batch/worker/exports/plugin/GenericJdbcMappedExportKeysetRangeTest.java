package io.github.pinpols.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.worker.exports.plugin.GenericJdbcMappedExportDataPlugin.DetailSql;
import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("通用 JDBC 映射导出 keyset 区间谓词单测:区间边界,回退与游标共存语义")
class GenericJdbcMappedExportKeysetRangeTest {

  private static final DetailSql DETAIL =
      new DetailSql("\"id\",\"v\"", "biz.\"t\"", "\"batch_id\"", "\"id\"");

  @Test
  @DisplayName("生效且非末片:使用左闭右开区间,上下界参数按序绑定")
  void shouldUseHalfOpenRange_whenActiveNonLastPartition() {
    var range = new ExportKeysetRange(true, new BigDecimal("0"), new BigDecimal("25"), false, 4, 1);

    var pq = GenericJdbcMappedExportDataPlugin.buildDetailQuery(DETAIL, 7L, null, 500, range);

    assertThat(pq.sql()).contains("\"id\" >= ?").contains("\"id\" < ?").doesNotContain("hashtext");
    var args = Arrays.asList(pq.args());
    assertThat(args).contains(new BigDecimal("0"), new BigDecimal("25"));
    assertThat(args.indexOf(new BigDecimal("0"))).isLessThan(args.indexOf(new BigDecimal("25")));
  }

  @Test
  @DisplayName("生效且为末片:上界改为闭区间,避免漏掉最大值")
  void shouldUseClosedUpperBound_whenActiveLastPartition() {
    var range =
        new ExportKeysetRange(true, new BigDecimal("75"), new BigDecimal("100"), true, 4, 4);

    var pq = GenericJdbcMappedExportDataPlugin.buildDetailQuery(DETAIL, 7L, null, 500, range);

    assertThat(pq.sql()).contains("\"id\" >= ?").contains("\"id\" <= ?").doesNotContain("hashtext");
  }

  @Test
  @DisplayName("未生效:退回哈希分片谓词,并绑定分片总数")
  void shouldFallBackToHashtext_whenInactive() {
    var range = ExportKeysetRange.inactiveFor(4, 2);

    var pq = GenericJdbcMappedExportDataPlugin.buildDetailQuery(DETAIL, 7L, null, 500, range);

    assertThat(pq.sql()).contains("hashtext");
    assertThat(pq.args()).contains(4);
  }

  @Test
  @DisplayName("生效且带游标:区间谓词在前,游标在其后,分页上限仍在最末")
  void shouldKeepRangeBeforeCursor_whenActiveWithCursor() {
    var range = new ExportKeysetRange(true, new BigDecimal("0"), new BigDecimal("25"), false, 4, 1);

    var pq = GenericJdbcMappedExportDataPlugin.buildDetailQuery(DETAIL, 7L, 10L, 500, range);

    String sql = pq.sql();
    assertThat(sql).contains("\"id\" >= ?").contains("\"id\" < ?").contains("\"id\" > ?");
    // 区间谓词在前、cursor 在后、LIMIT 末尾
    assertThat(sql.indexOf("\"id\" >= ?")).isLessThan(sql.indexOf("\"id\" > ?"));
    assertThat(sql.indexOf("\"id\" > ?")).isLessThan(sql.indexOf("LIMIT"));
    assertThat(pq.args()).containsExactly(7L, new BigDecimal("0"), new BigDecimal("25"), 10L, 500);
  }
}
