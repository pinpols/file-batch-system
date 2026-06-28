package io.github.pinpols.batch.common.tenant.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.testing.TestContainerImages;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * P2 tenant-routing 路由正确性 IT(CI 常驻,自带 Testcontainers 双 biz PG,无 env-gate)。
 *
 * <p>把原 env-gated 活体测试({@code BusinessMultiShardRoutingLiveTest})变成 CI 可跑:两个真实 PG 容器 = shard-0 /
 * shard-1,验证 {@link BusinessRoutingDataSourceFactory#multiShard} + {@link
 * HashAndSiloPlacementResolver}(config 路由)与 {@link DbTablePlacementResolver}(表覆盖)在<b>真实跨实例</b>
 * 下把每个租户的连接物理路由到选定片。识别落片:经路由 DS 读回各片 {@code biz.__shard_identity}。
 */
class BusinessMultiShardRoutingIntegrationTest {

  private static final DockerImageName PG = DockerImageName.parse(TestContainerImages.POSTGRES);

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer SHARD0 =
      new PostgreSQLContainer(PG)
          .withDatabaseName("batch_business")
          .withUrlParam("sslmode", "disable");

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer SHARD1 =
      new PostgreSQLContainer(PG)
          .withDatabaseName("batch_business")
          .withUrlParam("sslmode", "disable");

  private static HikariDataSource ds0;
  private static HikariDataSource ds1;

  @BeforeAll
  static void startAndSeed() throws Exception {
    SHARD0.start();
    SHARD1.start();
    ds0 = hikari(SHARD0);
    ds1 = hikari(SHARD1);
    seedShard(ds0, "shard-0");
    seedShard(ds1, "shard-1");
    // placement 表放 shard-0 容器(本测当它作 platform 存储),TABLE 模式从这里读
    try (Connection c = ds0.getConnection();
        Statement st = c.createStatement()) {
      st.execute("CREATE SCHEMA IF NOT EXISTS batch");
      st.execute(
          "CREATE TABLE IF NOT EXISTS batch.business_tenant_placement (tenant_id varchar(64)"
              + " PRIMARY KEY, placement_key varchar(64) NOT NULL, updated_at timestamptz NOT NULL"
              + " DEFAULT now(), updated_by varchar(128))");
    }
  }

  @AfterAll
  static void stop() {
    if (ds0 != null) ds0.close();
    if (ds1 != null) ds1.close();
    SHARD0.stop();
    SHARD1.stop();
  }

  @AfterEach
  void clearContext() {
    RlsTenantContextHolder.clear();
  }

  @Test
  @DisplayName("config 路由:每租户经 multiShard 物理落到 hash 选定片(双真实 PG)")
  void configHashRoutesToResolvedShard() throws Exception {
    HashAndSiloPlacementResolver resolver = new HashAndSiloPlacementResolver(2, Map.of());
    DataSource routing = BusinessRoutingDataSourceFactory.multiShard(shardMap(), resolver);

    for (String tenant : new String[] {"t-a", "t-b", "t-c", "t-d", "acme", "globex"}) {
      RlsTenantContextHolder.set(tenant);
      assertThat(readIdentity(routing))
          .as("租户 %s 应落 %s", tenant, resolver.resolve(tenant))
          .isEqualTo(resolver.resolve(tenant));
      RlsTenantContextHolder.clear();
    }
  }

  @Test
  @DisplayName("table 路由:placement 表显式映射覆盖 hash(table wins),未登记走 hash")
  void tablePlacementOverridesHash() throws Exception {
    HashAndSiloPlacementResolver hash = new HashAndSiloPlacementResolver(2, Map.of());
    String tenant = "table-driven-tenant";
    String tableKey = "shard-0".equals(hash.resolve(tenant)) ? "shard-1" : "shard-0"; // 反片
    try (Connection c = ds0.getConnection();
        Statement st = c.createStatement()) {
      st.execute(
          "INSERT INTO batch.business_tenant_placement(tenant_id,placement_key,updated_by) VALUES"
              + " ('"
              + tenant
              + "','"
              + tableKey
              + "','it') ON CONFLICT (tenant_id) DO UPDATE SET"
              + " placement_key=EXCLUDED.placement_key");
    }
    DbTablePlacementResolver resolver =
        new DbTablePlacementResolver(placementRepo(), hash, 0L, System::currentTimeMillis);
    DataSource routing = BusinessRoutingDataSourceFactory.multiShard(shardMap(), resolver);

    RlsTenantContextHolder.set(tenant);
    assertThat(readIdentity(routing))
        .as("登记租户应落表里的 %s(覆盖 hash %s)", tableKey, hash.resolve(tenant))
        .isEqualTo(tableKey);
    RlsTenantContextHolder.clear();

    RlsTenantContextHolder.set("unlisted-tenant");
    assertThat(readIdentity(routing)).as("未登记走 hash").isEqualTo(hash.resolve("unlisted-tenant"));
  }

  private static Map<String, DataSource> shardMap() {
    Map<String, DataSource> m = new LinkedHashMap<>();
    m.put("shard-0", ds0);
    m.put("shard-1", ds1);
    return m;
  }

  /** 直读 placement 表的 repository(IT 桩;生产走 MyBatisTenantPlacementRepository)。 */
  private static TenantPlacementRepository placementRepo() {
    return () -> {
      Map<String, String> map = new LinkedHashMap<>();
      try (Connection c = ds0.getConnection();
          Statement st = c.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT tenant_id, placement_key FROM batch.business_tenant_placement")) {
        while (rs.next()) {
          map.put(rs.getString(1), rs.getString(2));
        }
      } catch (Exception ex) {
        return Map.of();
      }
      return map;
    };
  }

  private static HikariDataSource hikari(PostgreSQLContainer pg) {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(pg.getJdbcUrl());
    cfg.setUsername(pg.getUsername());
    cfg.setPassword(pg.getPassword());
    cfg.setMaximumPoolSize(2);
    return new HikariDataSource(cfg);
  }

  private static void seedShard(DataSource ds, String key) throws Exception {
    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.execute("CREATE SCHEMA IF NOT EXISTS biz");
      st.execute(
          "CREATE TABLE IF NOT EXISTS biz.__shard_identity (only_one boolean PRIMARY KEY DEFAULT"
              + " true, shard_key text NOT NULL)");
      st.execute(
          "INSERT INTO biz.__shard_identity(only_one,shard_key) VALUES (true,'"
              + key
              + "') ON CONFLICT (only_one) DO UPDATE SET shard_key=EXCLUDED.shard_key");
    }
  }

  private static String readIdentity(DataSource routing) throws Exception {
    try (Connection c = routing.getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT shard_key FROM biz.__shard_identity")) {
      assertThat(rs.next()).isTrue();
      return rs.getString(1);
    }
  }
}
