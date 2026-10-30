package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.config.param.ConfigSyncLogInsertParam;
import io.github.pinpols.batch.console.domain.config.param.ConfigSyncLogUpdateParam;
import io.github.pinpols.batch.console.mapper.ConfigSyncLogMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 真实PG验证具名参数的生成主键、租户限定与JSON合并/替换及状态保护。 */
@SpringBootTest(classes = BatchConsoleApiApplication.class)
@DisplayName("配置同步审计具名Mapper：主键、租户及状态和JSON语义")
class ConfigSyncTypedMapperIntegrationTest extends AbstractIntegrationTest {
  private final ConfigSyncLogMapper mapper;
  private final JdbcTemplate jdbc;

  @Autowired
  ConfigSyncTypedMapperIntegrationTest(ConfigSyncLogMapper mapper, JdbcTemplate jdbc) {
    this.mapper = mapper;
    this.jdbc = jdbc;
  }

  @Test
  @DisplayName("数据库回填主键，拒绝跨租户与终态退回，并保留JSON合并和替换分支")
  void shouldPreserveIdTenantAndJsonContracts_whenWritingAudit() {
    String tenant = "sync-typed-" + Long.toUnsignedString(System.nanoTime());
    ConfigSyncLogInsertParam input = ConfigSyncLogInsertParam.builder()
        .tenantId(tenant)
        .syncDirection("IMPORT")
        .sourceEnv("source")
        .targetEnv("target")
        .configTypes("jobDefinitions")
        .totalItems(3)
        .successItems(0)
        .failedItems(0)
        .skippedItems(0)
        .syncStatus("RUNNING")
        .detailJson("{\"original\":1}")
        .operatorId("typed-it")
        .build();
    assertThat(mapper.insert(input)).isEqualTo(1);
    assertThat(input.getId()).isPositive();
    Long id = input.getId();
    ConfigSyncLogUpdateParam wrongTenant = update(tenant + "-other", id, "SUCCESS", true, "{}");
    assertThat(mapper.updateResult(wrongTenant)).isZero();
    ConfigSyncLogUpdateParam merge =
        update(tenant, id, "RUNNING", true, "{\"outcomeKnown\":false}");
    assertThat(mapper.updateResult(merge)).isEqualTo(1);
    Map<String, Object> merged = state(tenant, id);
    assertThat(merged)
        .containsEntry("sync_status", "RUNNING")
        .containsEntry("original", "1")
        .containsEntry("outcome_known", "false");
    ConfigSyncLogUpdateParam finish = update(tenant, id, "SUCCESS", true, "{\"result\":\"ok\"}");
    assertThat(mapper.updateResult(finish)).isEqualTo(1);
    assertThat(mapper.updateResult(merge)).isZero();
    Map<String, Object> terminal = state(tenant, id);
    assertThat(terminal)
        .containsEntry("sync_status", "SUCCESS")
        .containsEntry("original", "1")
        .containsEntry("result", "ok");
    ConfigSyncLogUpdateParam replace =
        update(tenant, id, "SUCCESS", false, "{\"replacement\":true}");
    assertThat(mapper.updateResult(replace)).isEqualTo(1);
    Map<String, Object> replaced = state(tenant, id);
    assertThat(replaced)
        .containsEntry("sync_status", "SUCCESS")
        .containsEntry("original", null)
        .containsEntry("replacement", "true");
  }

  private static ConfigSyncLogUpdateParam update(
      String tenant, Long id, String status, boolean merge, String json) {
    return ConfigSyncLogUpdateParam.builder()
        .tenantId(tenant)
        .id(id)
        .syncStatus(status)
        .successItems(2)
        .failedItems(1)
        .skippedItems(0)
        .mergeDetail(merge)
        .detailJson(json)
        .build();
  }

  private Map<String, Object> state(String tenant, Long id) {
    String readSql = """
        select sync_status, detail_json->>'original' as original,
               detail_json->>'outcomeKnown' as outcome_known, detail_json->>'result' as result,
               detail_json->>'replacement' as replacement
        from batch.config_sync_log where tenant_id = ? and id = ?
        """;
    return jdbc.queryForMap(readSql, tenant, id);
  }
}
