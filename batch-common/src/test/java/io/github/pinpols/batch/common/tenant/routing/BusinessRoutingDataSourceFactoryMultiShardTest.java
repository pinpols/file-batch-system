package io.github.pinpols.batch.common.tenant.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import java.sql.Connection;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BusinessRoutingDataSourceFactoryMultiShardTest {

  @AfterEach
  void clear() {
    RlsTenantContextHolder.clear();
  }

  @Test
  @DisplayName("多片:每个租户路由到 resolver 选定 shard 的连接")
  void routesTenantToResolvedShard() throws Exception {
    DataSource ds0 = mock(DataSource.class);
    DataSource ds1 = mock(DataSource.class);
    Connection c0 = mock(Connection.class);
    Connection c1 = mock(Connection.class);
    // lenient:某些 tenant 分布下可能只命中一片,另一片 stub 不被用,非错误
    lenient().when(ds0.getConnection()).thenReturn(c0);
    lenient().when(ds1.getConnection()).thenReturn(c1);
    HashAndSiloPlacementResolver resolver = new HashAndSiloPlacementResolver(2, Map.of());
    Map<String, DataSource> shards = Map.of("shard-0", ds0, "shard-1", ds1);
    DataSource routing = BusinessRoutingDataSourceFactory.multiShard(shards, resolver);

    for (String t : new String[] {"ta", "tb", "tc", "td", "te"}) {
      RlsTenantContextHolder.set(t);
      Connection expected = "shard-0".equals(resolver.resolve(t)) ? c0 : c1;
      assertThat(routing.getConnection()).as("tenant %s 路由到选定 shard", t).isSameAs(expected);
      RlsTenantContextHolder.clear();
    }
  }

  @Test
  @DisplayName("silo 租户路由到 silo 数据源连接")
  void routesSiloTenant() throws Exception {
    DataSource ds0 = mock(DataSource.class);
    DataSource siloDs = mock(DataSource.class);
    Connection cs = mock(Connection.class);
    lenient().when(siloDs.getConnection()).thenReturn(cs);
    HashAndSiloPlacementResolver resolver =
        new HashAndSiloPlacementResolver(2, Map.of("big", "silo-big"));
    DataSource routing =
        BusinessRoutingDataSourceFactory.multiShard(
            Map.of("shard-0", ds0, "shard-1", mock(DataSource.class), "silo-big", siloDs),
            resolver);
    RlsTenantContextHolder.set("big");
    assertThat(routing.getConnection()).isSameAs(cs);
  }

  @Test
  @DisplayName("shards 缺 default key(shard-0)拒绝")
  void rejectsMissingDefaultKey() {
    assertThatThrownBy(
            () ->
                BusinessRoutingDataSourceFactory.multiShard(
                    Map.of("shard-1", mock(DataSource.class)),
                    new HashAndSiloPlacementResolver(2, Map.of())))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
