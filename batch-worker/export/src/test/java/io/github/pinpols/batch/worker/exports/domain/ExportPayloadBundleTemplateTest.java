package io.github.pinpols.batch.worker.exports.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-046 文件束导出:导出束 partition 的绑定 = templateCode(=源表/查询导出模板)。dispatch 派发把 partition.template_code
 * 写入 payload 的 templateCode(与 import 同机制),{@link ExportPayload} 字段名即为 templateCode,故 PrepareStep
 * 原有「payload.templateCode() 优先」逻辑直接路由束导出——零 worker 改。本测试固化该契约。
 */
@DisplayName("导出文件束模板绑定单测:载荷模板编码解析与未知字段忽略语义")
class ExportPayloadBundleTemplateTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("载荷中的模板编码被解析出来,供准备阶段路由到对应导出模板")
  void shouldParseTemplateCodeIntoPayload_whenRoutingBundleExport() throws Exception {
    String json = "{\"templateCode\":\"EXP_RISK\",\"targetRef\":\"sftp-a\"}";

    ExportPayload payload = objectMapper.readValue(json, ExportPayload.class);

    // PrepareStep 用 payload.templateCode() 路由到该导出模板(无需 <jobCode>_TPL 回退)
    assertThat(payload.templateCode()).isEqualTo("EXP_RISK");
  }

  @Test
  @DisplayName("载荷含导出无意义的束通用列时,反序列化安全忽略且不抛异常")
  void shouldIgnoreUnknownBundleKeys_whenDeserializingPayload() throws Exception {
    // 束通用列 sourceFileId/targetRef 对导出无意义,ignoreUnknown 安全丢弃,不抛异常
    String json = "{\"templateCode\":\"EXP_TRADE\",\"sourceFileId\":7,\"targetRef\":\"oss-b\"}";

    ExportPayload payload = objectMapper.readValue(json, ExportPayload.class);

    assertThat(payload.templateCode()).isEqualTo("EXP_TRADE");
  }
}
