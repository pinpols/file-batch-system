package com.example.batch.testing.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 故障注入意图:验证 PG 连接在 toxiproxy disable 模拟 RST 后:
 *
 * <ul>
 *   <li>正在使用的连接抛 SQLException(应用按现有 retry 路径处理,如 outbox 写入的 CAS 重试)
 *   <li>HikariCP 在故障期间无法 borrow 新连接(short connectionTimeout 必须生效)
 *   <li>故障消除后池可自愈 — 下一次 getConnection 成功,且能写读 — 验"上线后 PG 抖动 → outbox 不丢、worker 复连"
 * </ul>
 *
 * <p>对应业务路径:HikariCP 自动 invalidate dead 连接 + Spring Retry 对 SQLException 的瞬时退避。
 */
@DisplayName("PG 连接 RST 注入 — HikariCP 借出连接抛 SQLException;故障消除后池自愈、新连接可读写")
class PgConnectionResetToxicIT extends AbstractChaosIntegrationTest {

  @Test
  @DisplayName("正在使用的连接遭遇 PG RST → 抛 SQLException(应用走重试路径)")
  void inUseConnectionShouldFailWhenPgGoesDown() throws Exception {
    try (HikariDataSource ds = newHikariDataSource()) {
      try (Connection conn = ds.getConnection();
          Statement stmt = conn.createStatement()) {
        // 健康路径预热
        stmt.executeQuery("SELECT 1").close();

        withDown(
            ProxyTarget.PG,
            () ->
                assertThatThrownBy(
                        () -> {
                          try (Statement s = conn.createStatement()) {
                            s.executeQuery("SELECT 1").close();
                          }
                        })
                    .isInstanceOf(SQLException.class));
      }
    }
  }

  @Test
  @DisplayName("PG 恢复 → Hikari 池可重新 borrow 连接、可读写,验证池自愈(outbox 写路径不丢)")
  void hikariPoolShouldSelfHealAfterPgRecovers() throws Exception {
    // 不预热 — 池里没有 idle 连接,getConnection 必触发"新建连接"路径,
    // 这样故障期间走 toxiproxy 真实失败,不被 Hikari 的 idle fast-path 绕过。
    try (HikariDataSource ds = newHikariDataSource()) {
      // 故障期间 borrow 必失败(connectionTimeout=2s)
      withDown(
          ProxyTarget.PG,
          () -> assertThatThrownBy(ds::getConnection).isInstanceOf(SQLException.class));

      // 退出 withDown 后 toxic 已移除 — 池必须自愈,新连接可写读
      String testTable = "chaos_pg_reset_" + UUID.randomUUID().toString().replace('-', '_');
      try (Connection c = ds.getConnection();
          Statement s = c.createStatement()) {
        s.execute("CREATE TEMP TABLE " + testTable + " (v int)");
        s.execute("INSERT INTO " + testTable + " VALUES (42)");
        var rs = s.executeQuery("SELECT v FROM " + testTable);
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt(1)).isEqualTo(42);
        rs.close();
      }
    }
  }

  private HikariDataSource newHikariDataSource() {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(pgProxiedJdbcUrl());
    cfg.setUsername("batch_user");
    cfg.setPassword("batch_pass_123");
    cfg.setMaximumPoolSize(3);
    cfg.setMinimumIdle(0);
    cfg.setConnectionTimeout(2000);
    cfg.setValidationTimeout(1500);
    cfg.setInitializationFailTimeout(-1); // 不在构造期校验,避免 IT 启动阶段被 toxic 残留波及
    return new HikariDataSource(cfg);
  }
}
