package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V145/V146 软删除复活语义的 SQL 行为合同测试 — 不走 controller 路径,直接 JdbcTemplate 验证 schema + mapper.xml
 * 的关键不变量,任何回归(如 V146 后续 migration 撤掉 upsert 复活、 mapper.xml 删了 is_deleted=false 复位、UNIQUE 约束改
 * partial 形成重复逻辑行等)立刻挂测。
 *
 * <p>覆盖 5 张配置表的关键不变量:
 *
 * <ul>
 *   <li>软删除 = UPDATE is_deleted=true(非物理 DELETE),原行 id 保留
 *   <li>列表查询默认 is_deleted=false 过滤,软删行不可见
 *   <li>upsert(ON CONFLICT)同 code 撞 UNIQUE 时复活 is_deleted=false,同一 id 不产生重复逻辑行
 *   <li>update / toggleEnabled 带 is_deleted=false guard,已删除行无法被改回 enabled=true
 * </ul>
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
class SoftDeleteRecoveryIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "int-sdr-ta";

  @Autowired private JdbcTemplate jdbc;

  @AfterEach
  void cleanup() {
    // 每个 case 收尾物理删除(本测试自己的异常数据 — 真实生产路径只软删)
    jdbc.update("DELETE FROM batch.file_channel_config WHERE tenant_id = ?", TENANT);
    jdbc.update("DELETE FROM batch.notification_channel WHERE tenant_id = ?", TENANT);
    jdbc.update("DELETE FROM batch.alert_routing_config WHERE tenant_id = ?", TENANT);
    jdbc.update("DELETE FROM batch.webhook_subscription WHERE tenant_id = ?", TENANT);
  }

  // ===== file_channel_config =====

  @Test
  @DisplayName("file_channel_config: 软删后 SELECT 不可见 + recreate 同 code 走 upsert 自动复活,id 保留")
  void fileChannelConfig_softDeleteThenRecreate_revivesSameId() {
    // 1) 新建
    jdbc.update(
        "INSERT INTO batch.file_channel_config "
            + "(tenant_id, channel_code, channel_name, channel_type, auth_type, config_json,"
            + " enabled) VALUES (?, 'sdr-ch-1', 'first', 'SFTP', 'PASSWORD', '{}'::jsonb, true)",
        TENANT);
    Long originalId =
        jdbc.queryForObject(
            "SELECT id FROM batch.file_channel_config WHERE tenant_id = ? AND channel_code ="
                + " 'sdr-ch-1'",
            Long.class,
            TENANT);
    assertThat(originalId).isNotNull();

    // 2) 软删除
    jdbc.update("UPDATE batch.file_channel_config SET is_deleted = true WHERE id = ?", originalId);

    // 3) 默认 SELECT 过滤(activePredicate) → 不可见
    Long visibleCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM batch.file_channel_config WHERE tenant_id = ? AND channel_code ="
                + " 'sdr-ch-1' AND is_deleted = false",
            Long.class,
            TENANT);
    assertThat(visibleCount).isZero();

    // 4) upsert 同 code → ON CONFLICT 复活
    jdbc.update(
        "INSERT INTO batch.file_channel_config "
            + "(tenant_id, channel_code, channel_name, channel_type, auth_type, config_json,"
            + " enabled) VALUES (?, 'sdr-ch-1', 'second', 'API', 'NONE', '{}'::jsonb, true)"
            + " ON CONFLICT (tenant_id, channel_code) DO UPDATE SET "
            + "  channel_name = excluded.channel_name, channel_type = excluded.channel_type,"
            + "  auth_type = excluded.auth_type, is_deleted = false,"
            + "  updated_at = current_timestamp",
        TENANT);

    // 5) id 保留(同一逻辑实体不产生重复逻辑行)+ 字段被新值覆盖 + is_deleted 复位
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT id, channel_name, channel_type, is_deleted FROM batch.file_channel_config "
                + "WHERE tenant_id = ? AND channel_code = 'sdr-ch-1'",
            TENANT);
    assertThat(row.get("id")).isEqualTo(originalId);
    assertThat(row.get("channel_name")).isEqualTo("second");
    assertThat(row.get("channel_type")).isEqualTo("API");
    assertThat(row.get("is_deleted")).isEqualTo(false);

    Long totalRows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM batch.file_channel_config WHERE tenant_id = ? AND channel_code ="
                + " 'sdr-ch-1'",
            Long.class,
            TENANT);
    assertThat(totalRows).isOne();
  }

  // ===== notification_channel =====

  @Test
  @DisplayName("notification_channel: insert 改 upsert 后,recreate 不撞 UNIQUE 直接复活")
  void notificationChannel_recreateAfterSoftDelete_doesNotCollideUnique() {
    jdbc.update(
        "INSERT INTO batch.notification_channel "
            + "(tenant_id, channel_code, channel_name, channel_type, config_json, enabled)"
            + " VALUES (?, 'sdr-nc-1', 'first', 'EMAIL', '{}'::jsonb, true)",
        TENANT);
    Long originalId =
        jdbc.queryForObject(
            "SELECT id FROM batch.notification_channel WHERE tenant_id = ? AND channel_code ="
                + " 'sdr-nc-1'",
            Long.class,
            TENANT);
    jdbc.update("UPDATE batch.notification_channel SET is_deleted = true WHERE id = ?", originalId);

    // 再次 insert 同 code — 模拟 NotificationChannelMapper.insert(已加 ON CONFLICT 复活)
    jdbc.update(
        "INSERT INTO batch.notification_channel "
            + "(tenant_id, channel_code, channel_name, channel_type, config_json, enabled)"
            + " VALUES (?, 'sdr-nc-1', 'second', 'DINGTALK', '{}'::jsonb, true)"
            + " ON CONFLICT (tenant_id, channel_code) DO UPDATE SET"
            + "  channel_name = excluded.channel_name, channel_type = excluded.channel_type,"
            + "  config_json = excluded.config_json, enabled = excluded.enabled,"
            + "  is_deleted = false, updated_at = CURRENT_TIMESTAMP",
        TENANT);

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT id, channel_name, channel_type, is_deleted FROM batch.notification_channel "
                + "WHERE tenant_id = ? AND channel_code = 'sdr-nc-1'",
            TENANT);
    assertThat(row.get("id")).isEqualTo(originalId);
    assertThat(row.get("channel_type")).isEqualTo("DINGTALK");
    assertThat(row.get("is_deleted")).isEqualTo(false);
  }

  // ===== alert_routing_config update guard =====

  @Test
  @DisplayName("alert_routing_config: update/toggleEnabled 必须带 is_deleted=false guard,已删行不应被改")
  void alertRoutingConfig_updateGuard_rejectsSoftDeletedRow() {
    jdbc.update(
        "INSERT INTO batch.alert_routing_config "
            + "(tenant_id, route_code, route_name, team, alert_group, severity, receiver, enabled)"
            + " VALUES (?, 'sdr-ar-1', 'name-1', 'ops', 'grp', 'WARN', 'r@x', true)",
        TENANT);
    Long id =
        jdbc.queryForObject(
            "SELECT id FROM batch.alert_routing_config WHERE tenant_id = ? AND route_code ="
                + " 'sdr-ar-1'",
            Long.class,
            TENANT);
    jdbc.update("UPDATE batch.alert_routing_config SET is_deleted = true WHERE id = ?", id);

    // 模拟 AlertRoutingConfigMapper.toggleEnabled — 带 guard
    int toggled =
        jdbc.update(
            "UPDATE batch.alert_routing_config SET enabled = false, updated_at ="
                + " current_timestamp WHERE tenant_id = ? AND id = ? AND is_deleted = false",
            TENANT,
            id);
    assertThat(toggled).as("toggleEnabled 应被 is_deleted guard 拦截,不影响 0 行").isZero();

    // 模拟 AlertRoutingConfigMapper.updateById — 带 guard
    int updated =
        jdbc.update(
            "UPDATE batch.alert_routing_config SET route_name = 'changed', updated_at ="
                + " current_timestamp WHERE tenant_id = ? AND id = ? AND is_deleted = false",
            TENANT,
            id);
    assertThat(updated).as("updateById 应被 is_deleted guard 拦截,不影响 0 行").isZero();

    // route_name 仍是原值(没被改)
    String routeName =
        jdbc.queryForObject(
            "SELECT route_name FROM batch.alert_routing_config WHERE id = ?", String.class, id);
    assertThat(routeName).isEqualTo("name-1");
  }

  // ===== webhook_subscription =====

  @Test
  @DisplayName("webhook_subscription: insert 改 upsert 后,recreate 同 name 自动复活")
  void webhookSubscription_recreateAfterSoftDelete_revives() {
    jdbc.update(
        "INSERT INTO batch.webhook_subscription "
            + "(tenant_id, name, callback_url, event_types, enabled)"
            + " VALUES (?, 'sdr-wh-1', 'https://a.example', 'job.*', true)",
        TENANT);
    Long id =
        jdbc.queryForObject(
            "SELECT id FROM batch.webhook_subscription WHERE tenant_id = ? AND name = 'sdr-wh-1'",
            Long.class,
            TENANT);
    jdbc.update("UPDATE batch.webhook_subscription SET is_deleted = true WHERE id = ?", id);

    jdbc.update(
        "INSERT INTO batch.webhook_subscription "
            + "(tenant_id, name, callback_url, event_types, enabled)"
            + " VALUES (?, 'sdr-wh-1', 'https://b.example', 'workflow.*', false)"
            + " ON CONFLICT (tenant_id, name) DO UPDATE SET"
            + "  callback_url = excluded.callback_url, event_types = excluded.event_types,"
            + "  enabled = excluded.enabled, is_deleted = false,"
            + "  updated_at = current_timestamp",
        TENANT);

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT id, callback_url, event_types, is_deleted FROM batch.webhook_subscription "
                + "WHERE tenant_id = ? AND name = 'sdr-wh-1'",
            TENANT);
    assertThat(row.get("id")).isEqualTo(id);
    assertThat(row.get("callback_url")).isEqualTo("https://b.example");
    assertThat(row.get("event_types")).isEqualTo("workflow.*");
    assertThat(row.get("is_deleted")).isEqualTo(false);
  }

  // ===== file_template_config (V144 已加 is_deleted, V146 配套 upsert 复活) =====

  @Test
  @DisplayName("file_template_config: V144 软删 + V146 upsert 复活 — 验证 toggleEnabled guard")
  void fileTemplateConfig_softDeleteAndToggleGuard() {
    jdbc.update(
        "INSERT INTO batch.file_template_config "
            + "(tenant_id, template_code, template_name, template_type, biz_type,"
            + " file_format_type, charset, encrypt_type, checksum_type, compress_type,"
            + " streaming_enabled, enabled, version)"
            + " VALUES (?, 'sdr-ft-1', 'tpl', 'IMPORT', 'settlement', 'DELIMITED', 'UTF-8',"
            + " 'NONE', 'NONE', 'NONE', false, true, 1)",
        TENANT);
    Long id =
        jdbc.queryForObject(
            "SELECT id FROM batch.file_template_config WHERE tenant_id = ? AND template_code ="
                + " 'sdr-ft-1'",
            Long.class,
            TENANT);
    jdbc.update("UPDATE batch.file_template_config SET is_deleted = true WHERE id = ?", id);

    // toggleEnabled 带 guard,已删除行不能再被 enable
    int toggled =
        jdbc.update(
            "UPDATE batch.file_template_config SET enabled = true, updated_at ="
                + " current_timestamp WHERE tenant_id = ? AND id = ? AND is_deleted = false",
            TENANT,
            id);
    assertThat(toggled).isZero();

    Boolean enabled =
        jdbc.queryForObject(
            "SELECT enabled FROM batch.file_template_config WHERE id = ?", Boolean.class, id);
    assertThat(enabled).as("toggleEnabled 应被 guard 拦截,enabled 仍为软删前的 true").isTrue();

    // 清理(file_template_config 复合键 version)
    jdbc.update("DELETE FROM batch.file_template_config WHERE tenant_id = ?", TENANT);
  }
}
