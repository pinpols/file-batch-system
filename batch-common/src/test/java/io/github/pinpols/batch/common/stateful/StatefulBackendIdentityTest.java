package io.github.pinpols.batch.common.stateful;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

  @ParameterizedTest
  @MethodSource("caseSensitiveLocations")
  @DisplayName("资源名的大小写指向不同位置时，不得合并有状态后端身份")
  void shouldPreserveResourceCase_whenBuildingBackendIdentity(
      String scenario, String originalIdentity, String otherIdentity) {
    assertThat(otherIdentity).as(scenario).isNotEqualTo(originalIdentity);
  }

  @Test
  @DisplayName("接入地址只归一化协议和主机，保留资源原始编码与既有查询身份")
  void shouldPreserveResourceEncodingAndEndpointParameters_whenCanonicalizingHost() {
    assertThat(StatefulBackendIdentity.database("jdbc:postgresql://DB.EXAMPLE/Orders%2F2026"))
        .isEqualTo("jdbc=jdbc:postgresql://db.example/Orders%2F2026");
    assertThat(StatefulBackendIdentity.s3(
            "HTTPS://STORE.EXAMPLE/Objects?namespace=Orders", "us-east-1", "batch"))
        .isEqualTo(
            "endpoint=https://store.example/Objects?namespace=Orders|region=us-east-1|bucket=batch");
    assertThat(StatefulBackendIdentity.redis("REDIS.EXAMPLE", 6379, 0, null, null))
        .isEqualTo("host=redis.example|port=6379|db=0");
  }

  private static Stream<Arguments> caseSensitiveLocations() {
    return Stream.of(
        Arguments.of(
            "数据库名",
            StatefulBackendIdentity.database("jdbc:postgresql://db/Orders"),
            StatefulBackendIdentity.database("jdbc:postgresql://db/orders")),
        Arguments.of(
            "Sentinel主名",
            StatefulBackendIdentity.redis("ignored", 6379, 0, "Orders", "redis:26379"),
            StatefulBackendIdentity.redis("ignored", 6379, 0, "orders", "redis:26379")),
        Arguments.of(
            "文件系统桶目录",
            StatefulBackendIdentity.filesystem("./target/store", "Orders"),
            StatefulBackendIdentity.filesystem("./target/store", "orders")),
        Arguments.of(
            "对象存储接入路径",
            StatefulBackendIdentity.s3("https://store/Objects", "us-east-1", "batch"),
            StatefulBackendIdentity.s3("https://store/objects", "us-east-1", "batch")));
  }

  @Test
  @DisplayName("非凭据查询参数不参与身份比较,同一位置得到稳定结果")
  void shouldIgnoreNonCredentialParams_whenComparingDatabaseLocation() {
    assertThat(StatefulBackendIdentity.database("jdbc:postgresql://platform-db/batch_platform"
            + "?currentSchema=batch&reWriteBatchedInserts=true"))
        .isEqualTo("jdbc=jdbc:postgresql://platform-db/batch_platform");
  }
}
