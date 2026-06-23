package io.github.pinpols.batch.common.tenant.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * P2 tenant-routing 活体验证:对两片<b>真实 PG 实例</b>跑 {@link HashAndSiloPlacementResolver} + {@link
 * BusinessRoutingDataSourceFactory#multiShard} ,证明每个租户的连接物理落到 resolver 选定的实例。
 *
 * <p>非 Testcontainers——连接由环境变量提供的外部实例(secrets/biz-shards/ 注入): {@code
 * BIZ_SHARD_0_URL/USERNAME/PASSWORD}、{@code BIZ_SHARD_1_URL/USERNAME/PASSWORD}。 未设 {@code
 * BIZ_SHARD_0_URL} 时整类跳过,故不影响常规 CI / 单测。由 {@code scripts/local/verify-biz-shard.sh} 起两片后注入 env 运行。
 *
 * <p>识别「连到哪片」:测试前用每片的直连 DS 在 {@code biz.__shard_identity} 写入该片 key,再经路由 DS 取连接读回 identity,与 {@code
 * resolver.resolve(tenant)} 比对——相等即证明路由 DS 真的连到了选定实例。
 */
@EnabledIfEnvironmentVariable(named = "BIZ_SHARD_0_URL", matches = ".+")
class BusinessMultiShardRoutingLiveTest {

  @AfterEach
  void clear() {
    RlsTenantContextHolder.clear();
  }

  @Test
  @DisplayName("两片真实 PG:每个租户路由到 resolver 选定实例(读回 shard identity 比对)")
  void routesTenantsToResolvedRealInstance() throws Exception {
    DataSource direct0 = directDataSource(0);
    DataSource direct1 = directDataSource(1);
    try (HikariDataSource ds0 = (HikariDataSource) direct0;
        HikariDataSource ds1 = (HikariDataSource) direct1) {
      seedIdentity(ds0, "shard-0");
      seedIdentity(ds1, "shard-1");

      HashAndSiloPlacementResolver resolver = new HashAndSiloPlacementResolver(2, Map.of());
      Map<String, DataSource> shards = new LinkedHashMap<>();
      shards.put("shard-0", ds0);
      shards.put("shard-1", ds1);
      DataSource routing = BusinessRoutingDataSourceFactory.multiShard(shards, resolver);

      for (String tenant : new String[] {"tenant-a", "tenant-b", "tenant-c", "tenant-d", "acme"}) {
        RlsTenantContextHolder.set(tenant);
        String expected = resolver.resolve(tenant);
        assertThat(readIdentity(routing))
            .as("租户 %s 应路由到 %s 实例", tenant, expected)
            .isEqualTo(expected);
        RlsTenantContextHolder.clear();
      }
    }
  }

  @Test
  @DisplayName("表驱动:placement 表显式映射覆盖 hash(table wins),未登记租户仍走 hash")
  void placementTableOverridesHash() throws Exception {
    DataSource direct0 = directDataSource(0);
    DataSource direct1 = directDataSource(1);
    DataSource platform = platformDataSource();
    try (HikariDataSource ds0 = (HikariDataSource) direct0;
        HikariDataSource ds1 = (HikariDataSource) direct1;
        HikariDataSource platformDs = (HikariDataSource) platform) {
      seedIdentity(ds0, "shard-0");
      seedIdentity(ds1, "shard-1");

      HashAndSiloPlacementResolver hash = new HashAndSiloPlacementResolver(2, Map.of());
      // 选一个 tenant,把表里映射设成 hash 的「反片」,以证明表覆盖了 hash
      String tenant = "table-driven-tenant";
      String hashKey = hash.resolve(tenant);
      String tableKey = "shard-0".equals(hashKey) ? "shard-1" : "shard-0";
      ensurePlacementRow(platformDs, tenant, tableKey);

      TenantPlacementRepository repo = platformPlacementRepository(platformDs);
      DbTablePlacementResolver resolver =
          new DbTablePlacementResolver(repo, hash, 0L, System::currentTimeMillis);
      Map<String, DataSource> shards = new LinkedHashMap<>();
      shards.put("shard-0", ds0);
      shards.put("shard-1", ds1);
      DataSource routing = BusinessRoutingDataSourceFactory.multiShard(shards, resolver);

      // 1) 登记租户:路由到表里的片(= 反片,证明 table wins)
      RlsTenantContextHolder.set(tenant);
      assertThat(readIdentity(routing))
          .as("登记租户应路由到表里的 %s(覆盖 hash 的 %s)", tableKey, hashKey)
          .isEqualTo(tableKey);
      RlsTenantContextHolder.clear();

      // 2) 未登记租户:仍走 hash
      String unlisted = "unlisted-tenant";
      RlsTenantContextHolder.set(unlisted);
      assertThat(readIdentity(routing)).as("未登记租户应走 hash").isEqualTo(hash.resolve(unlisted));
      RlsTenantContextHolder.clear();
    }
  }

  private static DataSource directDataSource(int shard) {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(env("BIZ_SHARD_" + shard + "_URL"));
    cfg.setUsername(env("BIZ_SHARD_" + shard + "_USERNAME"));
    cfg.setPassword(env("BIZ_SHARD_" + shard + "_PASSWORD"));
    cfg.setDriverClassName("org.postgresql.Driver");
    cfg.setMaximumPoolSize(2);
    cfg.setPoolName("biz-shard-" + shard + "-live");
    return new HikariDataSource(cfg);
  }

  private static void seedIdentity(DataSource ds, String key) throws Exception {
    try (Connection conn = ds.getConnection();
        Statement st = conn.createStatement()) {
      st.execute(
          "CREATE TABLE IF NOT EXISTS biz.__shard_identity (only_one boolean PRIMARY KEY DEFAULT"
              + " true, shard_key text NOT NULL)");
      st.execute(
          "INSERT INTO biz.__shard_identity (only_one, shard_key) VALUES (true, '"
              + key
              + "') ON CONFLICT (only_one) DO UPDATE SET shard_key = EXCLUDED.shard_key");
    }
  }

  private static String readIdentity(DataSource routing) throws Exception {
    try (Connection conn = routing.getConnection();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT shard_key FROM biz.__shard_identity")) {
      assertThat(rs.next()).as("shard identity 行应存在").isTrue();
      return rs.getString(1);
    }
  }

  private static DataSource platformDataSource() {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(env("BIZ_PLATFORM_URL"));
    cfg.setUsername(env("BIZ_PLATFORM_USERNAME"));
    cfg.setPassword(env("BIZ_PLATFORM_PASSWORD"));
    cfg.setDriverClassName("org.postgresql.Driver");
    cfg.setMaximumPoolSize(2);
    cfg.setPoolName("biz-platform-live");
    return new HikariDataSource(cfg);
  }

  private static void ensurePlacementRow(DataSource platform, String tenant, String key)
      throws Exception {
    try (Connection conn = platform.getConnection();
        Statement st = conn.createStatement()) {
      st.execute(
          "CREATE TABLE IF NOT EXISTS batch.business_tenant_placement (tenant_id varchar(64)"
              + " PRIMARY KEY, placement_key varchar(64) NOT NULL, updated_at timestamptz NOT NULL"
              + " DEFAULT now(), updated_by varchar(128))");
      st.execute(
          "INSERT INTO batch.business_tenant_placement (tenant_id, placement_key, updated_by)"
              + " VALUES ('"
              + tenant
              + "', '"
              + key
              + "', 'live-test') ON CONFLICT (tenant_id) DO UPDATE SET placement_key ="
              + " EXCLUDED.placement_key");
    }
  }

  /** 直读 platform placement 表的 repository(测试桩;生产路径走 MyBatisTenantPlacementRepository)。 */
  private static TenantPlacementRepository platformPlacementRepository(DataSource platform) {
    return () -> {
      Map<String, String> mapping = new LinkedHashMap<>();
      try (Connection conn = platform.getConnection();
          Statement st = conn.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT tenant_id, placement_key FROM batch.business_tenant_placement")) {
        while (rs.next()) {
          mapping.put(rs.getString(1), rs.getString(2));
        }
      } catch (Exception ex) {
        return Map.of();
      }
      return mapping;
    };
  }

  private static String env(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("缺环境变量 " + name + "(由 secrets/biz-shards/ 注入)");
    }
    return value;
  }
}
