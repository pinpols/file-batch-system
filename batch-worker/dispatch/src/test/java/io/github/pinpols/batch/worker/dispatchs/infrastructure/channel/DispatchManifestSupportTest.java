package io.github.pinpols.batch.worker.dispatchs.infrastructure.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.utils.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("出站清单:业务日期保持 ISO 文本，摘要与载荷一致")
class DispatchManifestSupportTest {

  private static final String BIZ_DATE = "2026-10-18";

  static Stream<Object> businessDates() {
    return Stream.of(java.sql.Date.valueOf(BIZ_DATE), LocalDate.parse(BIZ_DATE), BIZ_DATE);
  }

  @ParameterizedTest
  @MethodSource("businessDates")
  @DisplayName("JDBC 日期、LocalDate 和 ISO 文本都序列化为同一业务日期字符串")
  void businessDateIsIsoText(Object businessDate) {
    Map<String, Object> body = manifestBody(manifest(Map.of("id", 7L, "biz_date", businessDate)));

    assertThat(body)
        .containsEntry("bizDate", BIZ_DATE)
        .containsEntry("tenantId", "tenant-a")
        .containsEntry("schemaVersion", "dispatch-sidecar-manifest-v1")
        .containsEntry("checksumType", "SHA-256")
        .containsEntry("checksumValue", "payload-checksum");
  }

  @Test
  @DisplayName("文件未登记业务日期时保留空值，不根据生成时刻猜测日期")
  void missingBusinessDateStaysNull() {
    assertThat(manifestBody(manifest(Map.of("id", 7L)))).containsEntry("bizDate", null);
  }

  @Test
  @DisplayName("清单自身的长度和摘要覆盖最终序列化字节")
  void manifestDigestCoversSerializedBytes() throws Exception {
    var result = manifest(Map.of("id", 7L, "biz_date", java.sql.Date.valueOf(BIZ_DATE)));

    assertThat(result.sizeBytes()).isEqualTo(result.bytes().length);
    assertThat(result.checksum()).isEqualTo(
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(result.bytes())));
    assertThat(manifestBody(result)).containsEntry("bizDate", BIZ_DATE);
  }

  private static DispatchManifestSupport.ManifestPayload manifest(Map<String, Object> fileRecord) {
    return DispatchManifestSupport.manifestPayload(
        new DispatchCommand("tenant-a", "trace-a", fileRecord, Map.of(), null),
        "sftp://host/outbound/daily.json",
        "daily.json",
        "request-a",
        "receipt-a",
        new DispatchManifestSupport.PayloadDigest(13L, "payload-checksum"),
        "sftp://host/outbound/daily.json.chk");
  }

  private static Map<String, Object> manifestBody(DispatchManifestSupport.ManifestPayload result) {
    return JsonUtils.fromJson(
        new String(result.bytes(), StandardCharsets.UTF_8), new TypeReference<>() {});
  }
}
