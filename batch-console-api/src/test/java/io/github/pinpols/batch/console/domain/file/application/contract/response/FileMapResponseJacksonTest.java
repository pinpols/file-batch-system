package io.github.pinpols.batch.console.domain.file.application.contract.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.FileStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** wire 红线守护：批次4 file 域 presign-upload 类型化 response record 的 JSON key 必须与历史 Map 响应逐字一致。 */
@DisplayName("文件预签名上传响应: 序列化字段与历史映射一致")
class FileMapResponseJacksonTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  @DisplayName("预签名上传响应序列化后保持九个字段与取值不变")
  void shouldKeepNineFixedKeysForPresignUpload() throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("fileId", 12L);
    row.put("status", FileStatus.RECEIVED.code());
    row.put("uploadMode", "APP_MANAGED");
    row.put("uploadMethod", "PUT");
    row.put("contentField", "file");
    row.put("uploadUrl", "/api/console/files/12/content?tenantId=acme");
    row.put("storageBucket", "bkt");
    row.put("storagePath", "uploads/acme/2026-07-11/x-y.csv");
    row.put("fileName", "y.csv");

    Map<String, Object> back = mapper.readValue(
        mapper.writeValueAsString(ConsoleFilePresignUploadResponse.from(row)),
        new TypeReference<>() {});

    assertThat(back)
        .containsOnlyKeys(
            "fileId",
            "status",
            "uploadMode",
            "uploadMethod",
            "contentField",
            "uploadUrl",
            "storageBucket",
            "storagePath",
            "fileName");
    assertThat(back).containsEntry("fileId", 12).containsEntry("uploadMethod", "PUT");
  }
}
