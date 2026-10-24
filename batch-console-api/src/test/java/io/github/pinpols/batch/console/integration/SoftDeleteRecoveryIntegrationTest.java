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
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("配置表软删除复活语义: 软删后不可见,同编码重建走 upsert 复活且保留原主键")
class SoftDeleteRecoveryIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "int-sdr-ta";

  @Autowired
  private JdbcTemplate jdbc;

  @AfterEach
  void cleanup() {
    final String deleteFileChannelConfigSql = """
        DELETE FROM batch.file_channel_config WHERE tenant_id = ?
    """;
    final String deleteNotificationChannelSql = """
        DELETE FROM batch.notification_channel WHERE tenant_id = ?
    """;
    final String deleteAlertRoutingConfigSql = """
        DELETE FROM batch.alert_routing_config WHERE tenant_id = ?
    """;
    final String deleteWebhookSubscriptionSql = """
        DELETE FROM batch.webhook_subscription WHERE tenant_id = ?
    """;

    // 每个 case 收尾物理删除(本测试自己的异常数据 — 真实生产路径只软删)
    jdbc.update(deleteFileChannelConfigSql, TENANT);
    jdbc.update(deleteNotificationChannelSql, TENANT);
    jdbc.update(deleteAlertRoutingConfigSql, TENANT);
    jdbc.update(deleteWebhookSubscriptionSql, TENANT);
  }

  // ===== file_channel_config =====

  @Test
  @DisplayName("file_channel_config: 软删后 SELECT 不可见 + recreate 同 code 走 upsert 自动复活,id 保留")
  void fileChannelConfig_softDeleteThenRecreate_revivesSameId() {
    final String insertFileChannelConfigSql = """
        INSERT INTO batch.file_channel_config (tenant_id, channel_code, channel_name, channel_type, auth_type,\s\
        config_json, enabled) VALUES (?, 'sdr-ch-1', 'first', 'SFTP', 'PASSWORD', '{}'::jsonb, true)\
        """;
    final String selectIdSql = """
        SELECT id FROM batch.file_channel_config WHERE tenant_id = ? AND channel_code = 'sdr-ch-1'\
        """;
    final String updateFileChannelConfigSql = """
        UPDATE batch.file_channel_config SET is_deleted = true WHERE id = ?
    """;
    final String selectCountSql = """
        SELECT COUNT(*) FROM batch.file_channel_config WHERE tenant_id = ? AND channel_code = 'sdr-ch-1'\s\
        AND is_deleted = false\
        """;
    final String insertFileChannelConfigSql2 = """
        INSERT INTO batch.file_channel_config (tenant_id, channel_code, channel_name, channel_type, auth_type,\s\
        config_json, enabled) VALUES (?, 'sdr-ch-1', 'second', 'API', 'NONE', '{}'::jsonb, true)\s\
        ON CONFLICT (tenant_id, channel_code) DO UPDATE\s\
        SET   channel_name = excluded.channel_name, channel_type = excluded.channel_type,  auth_type =\s\
        excluded.auth_type, is_deleted = false,  updated_at = current_timestamp\
        """;
    final String selectIdSql2 = """
        SELECT id, channel_name, channel_type, is_deleted FROM batch.file_channel_config WHERE tenant_id = ?\s\
        AND channel_code = 'sdr-ch-1'\
        """;
    final String selectCountSql2 = """
        SELECT COUNT(*) FROM batch.file_channel_config WHERE tenant_id = ? AND channel_code = 'sdr-ch-1'\
        """;

    // 1) 新建
    jdbc.update(insertFileChannelConfigSql, TENANT);
    Long originalId = jdbc.queryForObject(selectIdSql, Long.class, TENANT);
    assertThat(originalId).isNotNull();

    // 2) 软删除
    jdbc.update(updateFileChannelConfigSql, originalId);

    // 3) 默认 SELECT 过滤(activePredicate) → 不可见
    Long visibleCount = jdbc.queryForObject(selectCountSql, Long.class, TENANT);
    assertThat(visibleCount).isZero();

    // 4) upsert 同 code → ON CONFLICT 复活
    jdbc.update(insertFileChannelConfigSql2, TENANT);

    // 5) id 保留(同一逻辑实体不产生重复逻辑行)+ 字段被新值覆盖 + is_deleted 复位
    Map<String, Object> row = jdbc.queryForMap(selectIdSql2, TENANT);
    assertThat(row).containsEntry("id", originalId);
    assertThat(row).containsEntry("channel_name", "second");
    assertThat(row).containsEntry("channel_type", "API");
    assertThat(row).containsEntry("is_deleted", false);

    Long totalRows = jdbc.queryForObject(selectCountSql2, Long.class, TENANT);
    assertThat(totalRows).isOne();
  }

  // ===== notification_channel =====

  @Test
  @DisplayName("notification_channel: insert 改 upsert 后,recreate 不撞 UNIQUE 直接复活")
  void notificationChannel_recreateAfterSoftDelete_doesNotCollideUnique() {
    final String insertNotificationChannelSql = """
        INSERT INTO batch.notification_channel (tenant_id, channel_code, channel_name, channel_type,\s\
        config_json, enabled) VALUES (?, 'sdr-nc-1', 'first', 'EMAIL', '{}'::jsonb, true)\
        """;
    final String selectIdSql = """
        SELECT id FROM batch.notification_channel WHERE tenant_id = ? AND channel_code = 'sdr-nc-1'\
        """;
    final String updateNotificationChannelSql = """
        UPDATE batch.notification_channel SET is_deleted = true WHERE id = ?
    """;
    final String insertNotificationChannelSql2 = """
        INSERT INTO batch.notification_channel (tenant_id, channel_code, channel_name, channel_type,\s\
        config_json, enabled) VALUES (?, 'sdr-nc-1', 'second', 'DINGTALK', '{}'::jsonb, true)\s\
        ON CONFLICT (tenant_id, channel_code) DO UPDATE\s\
        SET  channel_name = excluded.channel_name, channel_type = excluded.channel_type,  config_json =\s\
        excluded.config_json, enabled = excluded.enabled,  is_deleted = false, updated_at = CURRENT_TIMESTAMP\
        """;
    final String selectIdSql2 = """
        SELECT id, channel_name, channel_type, is_deleted FROM batch.notification_channel WHERE tenant_id = ?\s\
        AND channel_code = 'sdr-nc-1'\
        """;

    jdbc.update(insertNotificationChannelSql, TENANT);
    Long originalId = jdbc.queryForObject(selectIdSql, Long.class, TENANT);
    jdbc.update(updateNotificationChannelSql, originalId);

    // 再次 insert 同 code — 模拟 NotificationChannelMapper.insert(已加 ON CONFLICT 复活)
    jdbc.update(insertNotificationChannelSql2, TENANT);

    Map<String, Object> row = jdbc.queryForMap(selectIdSql2, TENANT);
    assertThat(row).containsEntry("id", originalId);
    assertThat(row).containsEntry("channel_type", "DINGTALK");
    assertThat(row).containsEntry("is_deleted", false);
  }

  // ===== alert_routing_config update guard =====

  @Test
  @DisplayName("alert_routing_config: update/toggleEnabled 必须带 is_deleted=false guard,已删行不应被改")
  void alertRoutingConfig_updateGuard_rejectsSoftDeletedRow() {
    final String insertAlertRoutingConfigSql = """
        INSERT INTO batch.alert_routing_config (tenant_id, route_code, route_name, team, alert_group, severity,\s\
        receiver, enabled) VALUES (?, 'sdr-ar-1', 'name-1', 'ops', 'grp', 'WARN', 'r@x', true)\
        """;
    final String selectIdSql = """
        SELECT id FROM batch.alert_routing_config WHERE tenant_id = ? AND route_code = 'sdr-ar-1'\
        """;
    final String updateAlertRoutingConfigSql = """
        UPDATE batch.alert_routing_config SET is_deleted = true WHERE id = ?
    """;
    final String updateAlertRoutingConfigSql2 = """
        UPDATE batch.alert_routing_config SET enabled = false, updated_at = current_timestamp\s\
        WHERE tenant_id = ? AND id = ? AND is_deleted = false\
        """;
    final String updateAlertRoutingConfigSql3 = """
        UPDATE batch.alert_routing_config SET route_name = 'changed', updated_at = current_timestamp\s\
        WHERE tenant_id = ? AND id = ? AND is_deleted = false\
        """;
    final String selectRouteNameSql = """
        SELECT route_name FROM batch.alert_routing_config WHERE id = ?
    """;

    jdbc.update(insertAlertRoutingConfigSql, TENANT);
    Long id = jdbc.queryForObject(selectIdSql, Long.class, TENANT);
    jdbc.update(updateAlertRoutingConfigSql, id);

    // 模拟 AlertRoutingConfigMapper.toggleEnabled — 带 guard
    int toggled = jdbc.update(updateAlertRoutingConfigSql2, TENANT, id);
    assertThat(toggled).as("toggleEnabled 应被 is_deleted guard 拦截,不影响 0 行").isZero();

    // 模拟 AlertRoutingConfigMapper.updateById — 带 guard
    int updated = jdbc.update(updateAlertRoutingConfigSql3, TENANT, id);
    assertThat(updated).as("updateById 应被 is_deleted guard 拦截,不影响 0 行").isZero();

    // route_name 仍是原值(没被改)
    String routeName = jdbc.queryForObject(selectRouteNameSql, String.class, id);
    assertThat(routeName).isEqualTo("name-1");
  }

  // ===== webhook_subscription =====

  @Test
  @DisplayName("webhook_subscription: insert 改 upsert 后,recreate 同 name 自动复活")
  void webhookSubscription_recreateAfterSoftDelete_revives() {
    final String insertWebhookSubscriptionSql = """
        INSERT INTO batch.webhook_subscription (tenant_id, name, callback_url, event_types, enabled)\s\
        VALUES (?, 'sdr-wh-1', 'https://a.example', 'job.*', true)\
        """;
    final String selectIdSql = """
        SELECT id FROM batch.webhook_subscription WHERE tenant_id = ? AND name = 'sdr-wh-1'
    """;
    final String updateWebhookSubscriptionSql = """
        UPDATE batch.webhook_subscription SET is_deleted = true WHERE id = ?
    """;
    final String insertWebhookSubscriptionSql2 = """
        INSERT INTO batch.webhook_subscription (tenant_id, name, callback_url, event_types, enabled)\s\
        VALUES (?, 'sdr-wh-1', 'https://b.example', 'workflow.*', false) ON CONFLICT (tenant_id, name) DO UPDATE\s\
        SET  callback_url = excluded.callback_url, event_types = excluded.event_types,  enabled =\s\
        excluded.enabled, is_deleted = false,  updated_at = current_timestamp\
        """;
    final String selectIdSql2 = """
        SELECT id, callback_url, event_types, is_deleted FROM batch.webhook_subscription WHERE tenant_id = ?\s\
        AND name = 'sdr-wh-1'\
        """;

    jdbc.update(insertWebhookSubscriptionSql, TENANT);
    Long id = jdbc.queryForObject(selectIdSql, Long.class, TENANT);
    jdbc.update(updateWebhookSubscriptionSql, id);

    jdbc.update(insertWebhookSubscriptionSql2, TENANT);

    Map<String, Object> row = jdbc.queryForMap(selectIdSql2, TENANT);
    assertThat(row).containsEntry("id", id);
    assertThat(row).containsEntry("callback_url", "https://b.example");
    assertThat(row).containsEntry("event_types", "workflow.*");
    assertThat(row).containsEntry("is_deleted", false);
  }

  // ===== file_template_config (V144 已加 is_deleted, V146 配套 upsert 复活) =====

  @Test
  @DisplayName("file_template_config: V144 软删 + V146 upsert 复活 — 验证 toggleEnabled guard")
  void fileTemplateConfig_softDeleteAndToggleGuard() {
    final String insertFileTemplateConfigSql = """
        INSERT INTO batch.file_template_config (tenant_id, template_code, template_name, template_type,\s\
        biz_type, file_format_type, charset, encrypt_type, checksum_type, compress_type, streaming_enabled,\s\
        enabled, version) VALUES (?, 'sdr-ft-1', 'tpl', 'IMPORT', 'settlement', 'DELIMITED', 'UTF-8', 'NONE',\s\
        'NONE', 'NONE', false, true, 1)\
        """;
    final String selectIdSql = """
        SELECT id FROM batch.file_template_config WHERE tenant_id = ? AND template_code = 'sdr-ft-1'\
        """;
    final String updateFileTemplateConfigSql = """
        UPDATE batch.file_template_config SET is_deleted = true WHERE id = ?
    """;
    final String updateFileTemplateConfigSql2 = """
        UPDATE batch.file_template_config SET enabled = true, updated_at = current_timestamp WHERE tenant_id = ?\s\
        AND id = ? AND is_deleted = false\
        """;
    final String selectEnabledSql = """
        SELECT enabled FROM batch.file_template_config WHERE id = ?
    """;
    final String deleteFileTemplateConfigSql = """
        DELETE FROM batch.file_template_config WHERE tenant_id = ?
    """;

    jdbc.update(insertFileTemplateConfigSql, TENANT);
    Long id = jdbc.queryForObject(selectIdSql, Long.class, TENANT);
    jdbc.update(updateFileTemplateConfigSql, id);

    // toggleEnabled 带 guard,已删除行不能再被 enable
    int toggled = jdbc.update(updateFileTemplateConfigSql2, TENANT, id);
    assertThat(toggled).isZero();

    Boolean enabled = jdbc.queryForObject(selectEnabledSql, Boolean.class, id);
    assertThat(enabled).as("toggleEnabled 应被 guard 拦截,enabled 仍为软删前的 true").isTrue();

    // 清理(file_template_config 复合键 version)
    jdbc.update(deleteFileTemplateConfigSql, TENANT);
  }
}
