package io.github.pinpols.batch.common.stateful;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("有状态后端身份:数据库地址归一化与凭据剥离")
class StatefulBackendIdentityTest {

  @Test
  @DisplayName("构造数据库身份时剥离权限段凭据、查询参数与片段,并统一主机名大小写")
  void shouldStripCredentialsAndQuery_whenBuildingDatabaseIdentity() {
    String identity = StatefulBackendIdentity.database(
        "jdbc:postgresql://alice:p%40ss@DB.EXAMPLE:5432/batch_platform"
            + "?user=alice&password=top-secret&ssl=true#hidden");

    assertThat(identity).isEqualTo("jdbc=jdbc:postgresql://db.example:5432/batch_platform");
    assertThat(identity)
        .doesNotContain("alice")
        .doesNotContain("p%40ss")
        .doesNotContain("top-secret");
  }

  @Test
  @DisplayName("非凭据查询参数不参与身份比较,同一位置得到稳定结果")
  void shouldIgnoreNonCredentialParams_whenComparingDatabaseLocation() {
    assertThat(StatefulBackendIdentity.database("jdbc:postgresql://platform-db/batch_platform"
            + "?currentSchema=batch&reWriteBatchedInserts=true"))
        .isEqualTo("jdbc=jdbc:postgresql://platform-db/batch_platform");
  }
}
