package io.github.pinpols.batch.worker.dispatchs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 验证文件束绑定键与普通字段映射到 {@link DispatchPayload} 的优先级及兼容读取。
 */
@DisplayName("文件束分发载荷别名绑定:束前缀键映射到文件号与目标渠道,历史字段与泛化文件号键互不干扰")
class DispatchPayloadBundleAliasTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("束派发载荷只带束前缀键时,解析结果带出文件号与目标渠道")
  void shouldMapBundleKeysToFileIdAndChannelCode_whenBundlePayloadParsed() throws Exception {
    // 束派发注入的是带 bundle 前缀的键 bundleSourceFileId(数字)+ bundleTargetRef(下游渠道)
    String json = "{\"bundleSourceFileId\":42,\"bundleTargetRef\":\"CH_SFTP\"}";

    DispatchPayload payload = objectMapper.readValue(json, DispatchPayload.class);

    assertThat(payload.fileId()).isEqualTo("42");
    assertThat(payload.channelCode()).isEqualTo("CH_SFTP");
  }

  @Test
  @DisplayName("普通分发载荷沿用历史字段名时,文件号与渠道号照常解析且不受别名影响")
  void shouldKeepParsingHistoricalFieldNames_whenLegacyPayloadParsed() throws Exception {
    // 普通分发任务仍用历史字段名 fileId / channelCode,不受别名影响
    String json = "{\"fileId\":\"99\",\"channelCode\":\"CH_OSS\"}";

    DispatchPayload payload = objectMapper.readValue(json, DispatchPayload.class);

    assertThat(payload.fileId()).isEqualTo("99");
    assertThat(payload.channelCode()).isEqualTo("CH_OSS");
  }

  @Test
  @DisplayName("普通渠道字段与调度采用一致优先级，束目标仍然优先")
  void shouldResolveChannelFieldsWithStablePriority() throws Exception {
    String json = """
        {"targetChannelCode":"TARGET","dispatchChannelCode":"DISPATCH","channelCode":"PRIMARY"}
        """;
    assertThat(objectMapper.readValue(json, DispatchPayload.class).channelCode())
        .isEqualTo("PRIMARY");
    String reverse = """
        {"channelCode":"PRIMARY","dispatchChannelCode":"DISPATCH","targetChannelCode":"TARGET"}
        """;
    assertThat(objectMapper.readValue(reverse, DispatchPayload.class).channelCode())
        .isEqualTo("PRIMARY");
    String fallback = """
        {"channelCode":"  ","targetChannelCode":"TARGET","dispatchChannelCode":" DISPATCH "}
        """;
    assertThat(objectMapper.readValue(fallback, DispatchPayload.class).channelCode())
        .isEqualTo("DISPATCH");
    String bundle = """
        {"bundleTargetRef":"BUNDLE","channelCode":"PRIMARY","dispatchChannelCode":"DISPATCH"}
        """;
    assertThat(objectMapper.readValue(bundle, DispatchPayload.class).channelCode())
        .isEqualTo("BUNDLE");
  }

  @Test
  @DisplayName("文件号和束文件号同时出现时解析不应依赖 JSON 字段顺序")
  void shouldResolveConsistentFileIdWhenAliasAndCanonicalKeysCoexist() throws Exception {
    DispatchPayload first = objectMapper.readValue(
        "{\"fileId\":\"77\",\"bundleSourceFileId\":42,\"channelCode\":\"PRIMARY\",\"bundleTargetRef\":\"OTHER\"}",
        DispatchPayload.class);
    DispatchPayload second = objectMapper.readValue(
        "{\"bundleTargetRef\":\"OTHER\",\"channelCode\":\"PRIMARY\",\"bundleSourceFileId\":42,\"fileId\":\"77\"}",
        DispatchPayload.class);
    assertThat(first.fileId()).isEqualTo("42");
    assertThat(second.fileId()).isEqualTo("42");
    assertThat(first.channelCode()).isEqualTo("OTHER");
    assertThat(second.channelCode()).isEqualTo("OTHER");
  }

  @Test
  @DisplayName("显式创建器保留 runMode 兼容读取与 run_mode 序列化")
  void shouldPreserveRunModeWireContract() throws Exception {
    DispatchPayload payload = objectMapper.readValue(
        "{\"fileId\":\"77\",\"runMode\":\"RETRY\",\"futureField\":true}", DispatchPayload.class);
    assertThat(payload.runMode()).isEqualTo("RETRY");
    assertThat(objectMapper
            .readTree(objectMapper.writeValueAsString(payload))
            .get("run_mode")
            .asText())
        .isEqualTo("RETRY");
  }

  @Test
  @DisplayName("载荷同时带真文件号与泛化文件号键时,以真文件号为准且渠道号保持为空")
  void shouldNotOverrideFileId_whenGenericFileIdKeyPresent() throws Exception {
    // P1-3 防御:别名是带前缀的 bundleSourceFileId,泛化 sourceFileId 不再是别名,不会覆盖真 fileId。
    String json = "{\"fileId\":\"77\",\"sourceFileId\":1,\"targetRef\":\"X\"}";

    DispatchPayload payload = objectMapper.readValue(json, DispatchPayload.class);

    assertThat(payload.fileId()).isEqualTo("77");
    assertThat(payload.channelCode()).isNull();
  }
}
