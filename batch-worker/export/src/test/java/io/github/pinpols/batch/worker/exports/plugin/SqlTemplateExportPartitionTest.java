package io.github.pinpols.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlTemplateExportPartitionTest {

  @Test
  void shouldNotAddShardPredicate_whenSinglePartition() {
    String sql = SqlTemplateExportDataPlugin.buildPagedSql("SELECT * FROM t", "id", false, 1, 1);
    assertThat(sql).doesNotContain("hashtext");
  }

  @Test
  void shouldAddShardPredicate_whenMultiPartition() {
    String sql = SqlTemplateExportDataPlugin.buildPagedSql("SELECT * FROM t", "id", false, 4, 2);
    assertThat(sql).contains("((hashtext(base.\"id\"::text) % 4) + 4) % 4 = 1").contains("WHERE");
  }

  @Test
  void shouldCombineShardAndCursor_whenMultiPartitionWithCursor() {
    String sql = SqlTemplateExportDataPlugin.buildPagedSql("SELECT * FROM t", "id", true, 4, 1);
    assertThat(sql).contains("hashtext").contains("AND base.\"id\" > :__cursor");
  }
}
