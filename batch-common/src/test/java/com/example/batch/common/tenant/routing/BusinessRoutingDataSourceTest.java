package com.example.batch.common.tenant.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.rls.RlsTenantContextHolder;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BusinessRoutingDataSourceTest {

  /** 暴露 protected determineCurrentLookupKey 供断言。 */
  static final class Exposed extends BusinessRoutingDataSource {
    Exposed(BusinessPlacementResolver resolver) {
      super(resolver);
    }

    Object lookupKey() {
      return determineCurrentLookupKey();
    }
  }

  @AfterEach
  void clearTenant() {
    RlsTenantContextHolder.clear();
  }

  @Test
  @DisplayName("lookup key = 以当前租户(RlsTenantContextHolder)解析出的 placement")
  void shouldRouteByCurrentTenant() {
    Exposed ds = new Exposed(new HashAndSiloPlacementResolver(1, Map.of()));
    RlsTenantContextHolder.set("ta");
    assertThat(ds.lookupKey()).isEqualTo("shard-0");
  }

  @Test
  @DisplayName("无租户上下文 → 走 resolver 回退 key(不抛)")
  void shouldFallbackWhenNoTenant() {
    Exposed ds = new Exposed(new HashAndSiloPlacementResolver(4, Map.of()));
    RlsTenantContextHolder.clear();
    assertThat(ds.lookupKey()).isEqualTo(HashAndSiloPlacementResolver.DEFAULT_KEY);
  }

  @Test
  @DisplayName("silo 租户 → 路由到 silo key")
  void shouldRouteSiloTenant() {
    Exposed ds = new Exposed(new HashAndSiloPlacementResolver(4, Map.of("big", "silo-big")));
    RlsTenantContextHolder.set("big");
    assertThat(ds.lookupKey()).isEqualTo("silo-big");
  }
}
