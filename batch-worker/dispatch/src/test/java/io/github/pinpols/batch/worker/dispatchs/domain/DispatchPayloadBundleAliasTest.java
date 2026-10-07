package io.github.pinpols.batch.worker.dispatchs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-046 文件束分发:验证束 partition 派发的通用绑定键(sourceFileId/targetRef)经 {@code @JsonAlias} 映射进 {@link
 * DispatchPayload} 的 fileId/channelCode,使 PrepareDispatchStep 无需改分支即可处理束分发。
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
  @DisplayName("载荷同时带真文件号与泛化文件号键时,以真文件号为准且渠道号保持为空")
  void shouldNotOverrideFileId_whenGenericFileIdKeyPresent() throws Exception {
    // P1-3 防御:别名是带前缀的 bundleSourceFileId,泛化 sourceFileId 不再是别名,不会覆盖真 fileId。
    String json = "{\"fileId\":\"77\",\"sourceFileId\":1,\"targetRef\":\"X\"}";

    DispatchPayload payload = objectMapper.readValue(json, DispatchPayload.class);

    assertThat(payload.fileId()).isEqualTo("77");
    assertThat(payload.channelCode()).isNull();
  }
}
