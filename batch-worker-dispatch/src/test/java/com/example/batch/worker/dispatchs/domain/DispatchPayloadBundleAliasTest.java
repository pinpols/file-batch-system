package com.example.batch.worker.dispatchs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * ADR-046 文件束分发:验证束 partition 派发的通用绑定键(sourceFileId/targetRef)经 {@code @JsonAlias} 映射进 {@link
 * DispatchPayload} 的 fileId/channelCode,使 PrepareDispatchStep 无需改分支即可处理束分发。
 */
class DispatchPayloadBundleAliasTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void bundleGenericKeysMapIntoFileIdAndChannelCode() throws Exception {
    // 束派发注入的是通用列名 sourceFileId(数字)+ targetRef(下游渠道)
    String json = "{\"sourceFileId\":42,\"targetRef\":\"CH_SFTP\"}";

    DispatchPayload payload = objectMapper.readValue(json, DispatchPayload.class);

    assertThat(payload.fileId()).isEqualTo("42");
    assertThat(payload.channelCode()).isEqualTo("CH_SFTP");
  }

  @Test
  void historicalFieldNamesStillParse() throws Exception {
    // 普通分发任务仍用历史字段名 fileId / channelCode,不受别名影响
    String json = "{\"fileId\":\"99\",\"channelCode\":\"CH_OSS\"}";

    DispatchPayload payload = objectMapper.readValue(json, DispatchPayload.class);

    assertThat(payload.fileId()).isEqualTo("99");
    assertThat(payload.channelCode()).isEqualTo("CH_OSS");
  }
}
