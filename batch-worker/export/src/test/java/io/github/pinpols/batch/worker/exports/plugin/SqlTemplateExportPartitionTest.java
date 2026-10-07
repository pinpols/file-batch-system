package io.github.pinpols.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("模板导出分片谓词单测:单分片不分片,多分片取模与游标共存语义")
class SqlTemplateExportPartitionTest {

  @Test
  @DisplayName("仅 1 个分片时不追加分片谓词,保持原查询不变")
  void shouldNotAddShardPredicate_whenSinglePartition() {
    String sql = SqlTemplateExportDataPlugin.buildPagedSql("SELECT * FROM t", "id", false, 1, 1);
    assertThat(sql).doesNotContain("hashtext");
  }

  @Test
  @DisplayName("多分片时按分片序号与总数生成取模谓词,并补上过滤条件")
  void shouldAddShardPredicate_whenMultiPartition() {
    String sql = SqlTemplateExportDataPlugin.buildPagedSql("SELECT * FROM t", "id", false, 4, 2);
    assertThat(sql).contains("((hashtext(base.\"id\"::text) % 4) + 4) % 4 = 1").contains("WHERE");
  }

  @Test
  @DisplayName("多分片且带游标时,分片谓词与游标条件以并且关系共存")
  void shouldCombineShardAndCursor_whenMultiPartitionWithCursor() {
    String sql = SqlTemplateExportDataPlugin.buildPagedSql("SELECT * FROM t", "id", true, 4, 1);
    assertThat(sql).contains("hashtext").contains("AND base.\"id\" > :__cursor");
  }
}
