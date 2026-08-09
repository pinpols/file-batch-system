package io.github.pinpols.batch.worker.dispatchs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChannelConfigMergeTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shouldReturnEmptyForNullOrEmptyRow() {
    assertThat(ChannelConfigMerge.merge(null, objectMapper)).isEmpty();
    assertThat(ChannelConfigMerge.merge(Map.of(), objectMapper)).isEmpty();
  }

  /**
   * S-1.5：白名单模式下，仅 ALLOWED_CONFIG_KEYS 里登记的键能从 config_json overlay； 其他键（"endpoint" 这种通用占位名，或
   * "tenant_id" 这种策略列）一律被拒绝， 防止渠道配置通过 config_json 绕过管理员策略。
   */
  @Test
  void shouldOnlyOverlayWhitelistedKeysFromConfigJsonMap() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("tenant_id", "t1");
    row.put("channel_code", "sftp-01");
    row.put("sftp_host", "legacy.example.com");
    Map<String, Object> cj = new LinkedHashMap<>();
    cj.put("sftp_host", "override.example.com"); // 白名单内：允许覆盖
    cj.put("sftp_port", 2222); // 白名单内：允许设置
    cj.put("tenant_id", "should-not-override-column"); // 非白名单：忽略
    cj.put("enabled", false); // 策略字段：必须忽略（原黑名单漏了这个）
    cj.put("receipt_policy", "ASYNC"); // 策略字段：必须忽略
    cj.put("receipt_poll_url", "http://callback.example/receipt"); // 回执轮询端点：允许
    cj.put("dispatch_manifest_enabled", false); // sidecar manifest 开关：允许
    cj.put("dispatch_manifest_suffix", ".sha256"); // sidecar manifest 后缀：允许
    cj.put("random_key", "x"); // 未注册：忽略
    row.put("config_json", cj);

    Map<String, Object> merged = ChannelConfigMerge.merge(row, objectMapper);
    assertThat(merged).containsEntry("tenant_id", "t1");
    assertThat(merged).containsEntry("sftp_host", "override.example.com");
    assertThat(merged).containsEntry("sftp_port", 2222);
    assertThat(merged).containsEntry("receipt_poll_url", "http://callback.example/receipt");
    assertThat(merged).containsEntry("dispatch_manifest_enabled", false);
    assertThat(merged).containsEntry("dispatch_manifest_suffix", ".sha256");
    assertThat(merged).doesNotContainKeys("enabled", "receipt_policy", "random_key");
  }

  @Test
  void shouldParseConfigJsonStringAndApplyWhitelist() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put(
        "config_json",
        "{\"sftp_host\":\"example.com\",\"tenant_id\":\"ignored\",\"random_ext\":\"x\"}");

    Map<String, Object> merged = ChannelConfigMerge.merge(row, objectMapper);
    assertThat(merged).containsEntry("sftp_host", "example.com");
    assertThat(merged).doesNotContainKeys("ignored", "tenant_id", "random_ext");
  }

  @Test
  void shouldNormalizeLegacyOssAliasesToCanonicalKeys() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put(
        "config_json",
        Map.of(
            "endpoint", "http://objectStore:9000",
            "bucket", "batch-dev",
            "prefix", "tb/outbound/statement/"));

    Map<String, Object> merged = ChannelConfigMerge.merge(row, objectMapper);

    assertThat(merged).containsEntry("target_endpoint", "http://objectStore:9000");
    assertThat(merged).containsEntry("oss_bucket", "batch-dev");
    assertThat(merged).containsEntry("oss_object_prefix", "tb/outbound/statement/");
    assertThat(merged).doesNotContainKeys("endpoint", "bucket", "prefix");
  }

  @Test
  void shouldNormalizeLegacyLocalDirectoryAndIgnoreLocalFileName() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put(
        "config_json",
        Map.of("local_directory", "/tmp/batch/outbox", "local_file_name", "result.csv"));

    Map<String, Object> merged = ChannelConfigMerge.merge(row, objectMapper);

    assertThat(merged).containsEntry("target_endpoint", "/tmp/batch/outbox");
    assertThat(merged).doesNotContainKeys("local_directory", "local_file_name");
  }
}
