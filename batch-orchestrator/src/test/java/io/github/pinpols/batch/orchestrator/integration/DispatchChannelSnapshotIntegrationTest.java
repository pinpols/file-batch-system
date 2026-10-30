package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.dto.FrozenDispatchChannelSnapshot;
import io.github.pinpols.batch.common.enums.ConfigVersionPolicy;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.service.DispatchChannelSnapshotService;
import io.github.pinpols.batch.orchestrator.service.DispatchSecretSnapshotResolver;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("普通分发渠道快照在部分识别结果缺失时仍保留原配置")
class DispatchChannelSnapshotIntegrationTest extends AbstractIntegrationTest {

  private final DispatchChannelSnapshotService snapshots;

  private final SecretPayloadProtector protector;

  private final DispatchSecretSnapshotResolver secretSnapshots;

  private final JdbcTemplate jdbc;

  @Autowired
  DispatchChannelSnapshotIntegrationTest(
      DispatchChannelSnapshotService snapshots,
      SecretPayloadProtector protector,
      DispatchSecretSnapshotResolver secretSnapshots,
      JdbcTemplate jdbc) {
    this.snapshots = snapshots;
    this.protector = protector;
    this.secretSnapshots = secretSnapshots;
    this.jdbc = jdbc;
  }

  @Test
  @DisplayName("无关停用渠道存在于普通启动快照时，两种重放仍保留实际目标")
  void shouldReplay_whenOptionalChannelIsDisabled() {
    final String insertChannelSql = """
        insert into batch.file_channel_config
          (tenant_id, channel_code, channel_name, channel_type, target_endpoint, config_json, enabled)
        values (?, ?, 'test', 'LOCAL', '/target', '{}'::jsonb, ?)
        """;
    jdbc.update(insertChannelSql, "tenant-a", "ENABLED_TARGET", true);
    jdbc.update(insertChannelSql, "tenant-a", "DISABLED_OPTIONAL", false);
    Map<String, Object> params = Map.of(
        "channelCode", "ENABLED_TARGET", "metadata", Map.of("targetRef", "DISABLED_OPTIONAL"));
    Map<String, Object> original = snapshots.pin("tenant-a", JobType.DISPATCH.code(), params);
    Map<?, ?> captured = (Map<?, ?>) original.get(FrozenDispatchChannelSnapshot.COLLECTION_KEY);
    assertThat(captured.containsKey("ENABLED_TARGET")).isTrue();
    assertThat(captured.containsKey("DISABLED_OPTIONAL")).isTrue();

    for (ConfigVersionPolicy policy : new ConfigVersionPolicy[] {
      ConfigVersionPolicy.USE_ORIGINAL_CONFIG, ConfigVersionPolicy.USE_LATEST_CONFIG
    }) {
      var selection = snapshots.captureForWorkflowReplay(
          new DispatchChannelSnapshotService.ReplayDispatchCaptureRequest(
              "tenant-a", JobType.DISPATCH.code(), params, policy, original, Map.of()));
      assertThat(selection.protectedSnapshots()).containsOnlyKeys("ENABLED_TARGET");
      assertThat(snapshots.pinWorkflowReplay(
              "tenant-a",
              JobType.DISPATCH.code(),
              params,
              selection.protectedSnapshots(),
              selection.secretBindings()))
          .containsEntry(
              FrozenDispatchChannelSnapshot.COLLECTION_KEY, selection.protectedSnapshots());
    }
  }

  @Test
  @DisplayName("普通启动留下无密钥的非目标候选时，两种重放均保留实际目标")
  void shouldReplay_whenOptionalChannelHasNoSecretVersion() {
    final String insertChannelSql = """
        insert into batch.file_channel_config
          (tenant_id, channel_code, channel_name, channel_type, target_endpoint, config_json)
        values (?, ?, 'test', 'LOCAL', '/target', cast(? as jsonb))
        """;
    jdbc.update(insertChannelSql, "tenant-a", "SECRET_TARGET", "{}");
    jdbc.update(
        insertChannelSql, "tenant-a", "SECRET_OPTIONAL", "{\"secret_ref\":\"missing/key\"}");
    Map<String, Object> params =
        Map.of("channelCode", "SECRET_TARGET", "metadata", Map.of("targetRef", "SECRET_OPTIONAL"));
    Map<String, Object> credentials =
        secretSnapshots.pin("tenant-a", JobType.DISPATCH.code(), params);
    Map<String, Object> original = snapshots.pin("tenant-a", JobType.DISPATCH.code(), credentials);
    Map<?, ?> captured = (Map<?, ?>) original.get(FrozenDispatchChannelSnapshot.COLLECTION_KEY);
    assertThat(captured.containsKey("SECRET_TARGET")).isTrue();
    assertThat(captured.containsKey("SECRET_OPTIONAL")).isTrue();

    for (ConfigVersionPolicy policy : new ConfigVersionPolicy[] {
      ConfigVersionPolicy.USE_ORIGINAL_CONFIG, ConfigVersionPolicy.USE_LATEST_CONFIG
    }) {
      var selection = snapshots.captureForWorkflowReplay(
          new DispatchChannelSnapshotService.ReplayDispatchCaptureRequest(
              "tenant-a", JobType.DISPATCH.code(), params, policy, original, Map.of()));
      assertThat(selection.protectedSnapshots()).containsOnlyKeys("SECRET_TARGET");
      assertThat(snapshots.pinWorkflowReplay(
              "tenant-a",
              JobType.DISPATCH.code(),
              params,
              selection.protectedSnapshots(),
              selection.secretBindings()))
          .containsEntry(
              FrozenDispatchChannelSnapshot.COLLECTION_KEY, selection.protectedSnapshots());
    }
  }

  @Test
  @DisplayName("重放保留实际渠道的原配置或最新配置并忽略无关业务元数据")
  void shouldFreezeSelectedReplayChannel_whenMetadataIsNotAChannel() {
    final String insertChannelSql = """
        insert into batch.file_channel_config
          (tenant_id, channel_code, channel_name, channel_type, target_endpoint, config_json)
        values (?, ?, 'test', 'LOCAL', '/old', '{}'::jsonb)
        """;
    final String updateChannelSql = """
        update batch.file_channel_config set target_endpoint = '/new'
        where tenant_id = ? and channel_code = ?
        """;
    String channel = "REPLAY_META_CH";
    jdbc.update(insertChannelSql, "tenant-a", channel);
    Map<String, Object> params =
        Map.of("channelCode", channel, "metadata", Map.of("targetRef", "not-a-channel"));
    Map<String, Object> original = snapshots.pin("tenant-a", JobType.DISPATCH.code(), params);
    assertThat(jdbc.update(updateChannelSql, "tenant-a", channel)).isEqualTo(1);

    for (ConfigVersionPolicy policy : new ConfigVersionPolicy[] {
      ConfigVersionPolicy.USE_ORIGINAL_CONFIG, ConfigVersionPolicy.USE_LATEST_CONFIG
    }) {
      var selection = snapshots.captureForWorkflowReplay(
          new DispatchChannelSnapshotService.ReplayDispatchCaptureRequest(
              "tenant-a", JobType.DISPATCH.code(), params, policy, original, Map.of()));
      assertThat(selection.protectedSnapshots()).containsOnlyKeys(channel);
      String protectedValue = selection.protectedSnapshots().get(channel);
      FrozenDispatchChannelSnapshot saved = JsonUtils.fromJsonStrict(
          protector.revealEncrypted(protectedValue), FrozenDispatchChannelSnapshot.class);
      assertThat(saved.configuration())
          .containsEntry(
              "target_endpoint",
              policy == ConfigVersionPolicy.USE_ORIGINAL_CONFIG ? "/old" : "/new");
      assertThat(snapshots.pinWorkflowReplay(
              "tenant-a",
              JobType.DISPATCH.code(),
              params,
              selection.protectedSnapshots(),
              Map.of()))
          .containsEntry(
              FrozenDispatchChannelSnapshot.COLLECTION_KEY, Map.of(channel, protectedValue));
    }
  }

  @Test
  @DisplayName("另一个识别字段不对应渠道时，真实渠道更新后快照仍保留原地址")
  void shouldRetainCapturedChannel_whenOtherCandidateIsMissing() {
    final String insertChannelSql = """
        insert into batch.file_channel_config
          (tenant_id, channel_code, channel_name, channel_type, target_endpoint, config_json)
        values (?, ?, 'test', 'LOCAL', '/old', '{}'::jsonb)
        """;
    final String updateChannelSql = """
        update batch.file_channel_config set target_endpoint = '/new'
        where tenant_id = ? and channel_code = ?
        """;
    final String selectChannelSql = """
        select target_endpoint from batch.file_channel_config where tenant_id = ? and channel_code = ?
        """;
    jdbc.update(insertChannelSql, "tenant-a", "CH1");
    Map<String, Object> params =
        Map.of("channelCode", "CH1", "metadata", Map.of("targetRef", "not-a-channel"));

    Map<String, Object> pinned = snapshots.pin("tenant-a", JobType.DISPATCH.code(), params);
    jdbc.update(updateChannelSql, "tenant-a", "CH1");

    assertThat(pinned.get(FrozenDispatchChannelSnapshot.COLLECTION_KEY)).isInstanceOf(Map.class);
    Map<?, ?> protectedSnapshots =
        (Map<?, ?>) pinned.get(FrozenDispatchChannelSnapshot.COLLECTION_KEY);
    String protectedSnapshot = (String) protectedSnapshots.get("CH1");
    String plaintext = protector.revealEncrypted(protectedSnapshot);
    FrozenDispatchChannelSnapshot saved =
        JsonUtils.fromJsonStrict(plaintext, FrozenDispatchChannelSnapshot.class);
    assertThat(saved.configuration()).containsEntry("target_endpoint", "/old");
    assertThat(jdbc.queryForObject(selectChannelSql, String.class, "tenant-a", "CH1"))
        .isEqualTo("/new");
  }
}
