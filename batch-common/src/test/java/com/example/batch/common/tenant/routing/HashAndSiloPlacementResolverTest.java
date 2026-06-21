package com.example.batch.common.tenant.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HashAndSiloPlacementResolverTest {

  @Test
  @DisplayName("单片无 silo:任何租户都落 shard-0(落地无损)")
  void shouldRouteAllToShard0_whenSingleShard() {
    HashAndSiloPlacementResolver r = new HashAndSiloPlacementResolver(1, Map.of());
    assertThat(r.resolve("ta")).isEqualTo("shard-0");
    assertThat(r.resolve("whatever-tenant")).isEqualTo("shard-0");
  }

  @Test
  @DisplayName("无租户上下文(null/空)→ shard-0 回退,不抛")
  void shouldFallbackToShard0_whenTenantBlank() {
    HashAndSiloPlacementResolver r = new HashAndSiloPlacementResolver(4, Map.of());
    assertThat(r.resolve(null)).isEqualTo("shard-0");
    assertThat(r.resolve("")).isEqualTo("shard-0");
    assertThat(r.resolve("   ")).isEqualTo("shard-0");
  }

  @Test
  @DisplayName("silo 租户路由到独占 key,优先于哈希分片")
  void shouldRouteSiloTenantToSiloKey() {
    HashAndSiloPlacementResolver r =
        new HashAndSiloPlacementResolver(4, Map.of("big-tenant", "silo-big"));
    assertThat(r.resolve("big-tenant")).isEqualTo("silo-big");
  }

  @Test
  @DisplayName("多片:同租户恒落同片(确定)且分布在 shard-0..N-1")
  void shouldBeDeterministicAndWithinShardRange() {
    int n = 4;
    HashAndSiloPlacementResolver r = new HashAndSiloPlacementResolver(n, Map.of());
    for (String t : new String[] {"ta", "tb", "tc", "td", "te", "tf"}) {
      String first = r.resolve(t);
      assertThat(r.resolve(t)).as("确定性:同租户两次一致").isEqualTo(first);
      assertThat(first).matches("shard-[0-3]");
    }
  }

  @Test
  @DisplayName("分片数 < 1 拒绝")
  void shouldRejectNonPositiveShardCount() {
    assertThatThrownBy(() -> new HashAndSiloPlacementResolver(0, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
