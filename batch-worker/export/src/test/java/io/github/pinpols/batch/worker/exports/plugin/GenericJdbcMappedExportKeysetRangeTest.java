package io.github.pinpols.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.worker.exports.plugin.GenericJdbcMappedExportDataPlugin.DetailSql;
import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class GenericJdbcMappedExportKeysetRangeTest {

  private static final DetailSql DETAIL =
      new DetailSql("\"id\",\"v\"", "biz.\"t\"", "\"batch_id\"", "\"id\"");

  @Test
  void shouldUseHalfOpenRange_whenActiveNonLastPartition() {
    var range = new ExportKeysetRange(true, new BigDecimal("0"), new BigDecimal("25"), false, 4, 1);

    var pq = GenericJdbcMappedExportDataPlugin.buildDetailQuery(DETAIL, 7L, null, 500, range);

    assertThat(pq.sql()).contains("\"id\" >= ?").contains("\"id\" < ?").doesNotContain("hashtext");
    var args = Arrays.asList(pq.args());
    assertThat(args).contains(new BigDecimal("0"), new BigDecimal("25"));
    assertThat(args.indexOf(new BigDecimal("0"))).isLessThan(args.indexOf(new BigDecimal("25")));
  }

  @Test
  void shouldUseClosedUpperBound_whenActiveLastPartition() {
    var range =
        new ExportKeysetRange(true, new BigDecimal("75"), new BigDecimal("100"), true, 4, 4);

    var pq = GenericJdbcMappedExportDataPlugin.buildDetailQuery(DETAIL, 7L, null, 500, range);

    assertThat(pq.sql()).contains("\"id\" >= ?").contains("\"id\" <= ?").doesNotContain("hashtext");
  }

  @Test
  void shouldFallBackToHashtext_whenInactive() {
    var range = ExportKeysetRange.inactiveFor(4, 2);

    var pq = GenericJdbcMappedExportDataPlugin.buildDetailQuery(DETAIL, 7L, null, 500, range);

    assertThat(pq.sql()).contains("hashtext");
    assertThat(pq.args()).contains(4);
  }

  @Test
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
