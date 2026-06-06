package com.example.batch.worker.exports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.worker.exports.plugin.GenericJdbcMappedExportDataPlugin.DetailSql;
import com.example.batch.worker.exports.plugin.GenericJdbcMappedExportDataPlugin.PagedQuery;
import org.junit.jupiter.api.Test;

class GenericJdbcMappedExportPartitionTest {

  @Test
  void shouldNotShard_whenSinglePartition() {
    PagedQuery pq =
        GenericJdbcMappedExportDataPlugin.buildDetailQuery(
            new DetailSql("c1,c2", "s.t", "\"fk\"", "\"id\""), 9L, null, 100, 1, 1);
    assertThat(pq.sql()).doesNotContain("hashtext");
    assertThat(pq.args()).containsExactly(9L, 100);
  }

  @Test
  void shouldShard_whenMultiPartition() {
    PagedQuery pq =
        GenericJdbcMappedExportDataPlugin.buildDetailQuery(
            new DetailSql("c1,c2", "s.t", "\"fk\"", "\"id\""), 9L, null, 100, 4, 3);
    assertThat(pq.sql()).contains("((hashtext(\"id\"::text) % ?) + ?) % ? = ?");
    assertThat(pq.args()).containsExactly(9L, 4, 4, 4, 2, 100);
  }
}
