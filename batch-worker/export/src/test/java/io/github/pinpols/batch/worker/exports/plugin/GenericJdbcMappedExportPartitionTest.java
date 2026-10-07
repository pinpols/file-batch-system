package io.github.pinpols.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.worker.exports.plugin.GenericJdbcMappedExportDataPlugin.DetailSql;
import io.github.pinpols.batch.worker.exports.plugin.GenericJdbcMappedExportDataPlugin.PagedQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("通用 JDBC 映射导出分片谓词单测:单分片不分片与多分片取模谓词语义")
class GenericJdbcMappedExportPartitionTest {

  @Test
  @DisplayName("仅 1 个分片时不加哈希分片谓词,参数只含租户与每页条数上限")
  void shouldNotShard_whenSinglePartition() {
    PagedQuery pq = GenericJdbcMappedExportDataPlugin.buildDetailQuery(
        new DetailSql("c1,c2", "s.t", "\"fk\"", "\"id\""), 9L, null, 100, 1, 1);
    assertThat(pq.sql()).doesNotContain("hashtext");
    assertThat(pq.args()).containsExactly(9L, 100);
  }

  @Test
  @DisplayName("多分片时追加哈希取模谓词,并按分片序号与总数绑定参数")
  void shouldShard_whenMultiPartition() {
    PagedQuery pq = GenericJdbcMappedExportDataPlugin.buildDetailQuery(
        new DetailSql("c1,c2", "s.t", "\"fk\"", "\"id\""), 9L, null, 100, 4, 3);
    assertThat(pq.sql()).contains("((hashtext(\"id\"::text) % ?) + ?) % ? = ?");
    assertThat(pq.args()).containsExactly(9L, 4, 4, 4, 2, 100);
  }
}
