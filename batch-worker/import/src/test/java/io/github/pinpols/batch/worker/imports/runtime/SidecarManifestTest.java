package io.github.pinpols.batch.worker.imports.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SidecarManifestTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("解析 file-sidecar-manifest-v1:全字段映射正确")
  void parsesFullManifest() throws Exception {
    String json =
        """
        {
          "schemaVersion": "file-sidecar-manifest-v1",
          "fileName": "settlement_20260607.dat",
          "sizeBytes": 12345678,
          "checksumType": "SHA-256",
          "checksumValue": "abc123",
          "recordCount": 100000,
          "bizDate": "2026-06-07"
        }
        """;
    SidecarManifest m = objectMapper.readValue(json, SidecarManifest.class);
    assertThat(m.fileName()).isEqualTo("settlement_20260607.dat");
    assertThat(m.sizeBytes()).isEqualTo(12345678L);
    assertThat(m.checksumType()).isEqualTo("SHA-256");
    assertThat(m.checksumValue()).isEqualTo("abc123");
    assertThat(m.recordCount()).isEqualTo(100000L);
  }

  @Test
  @DisplayName("未知字段忽略(前向兼容),缺省字段为 null")
  void ignoresUnknownAndAllowsMissing() throws Exception {
    String json = "{\"fileName\":\"a.csv\",\"futureField\":\"x\"}";
    SidecarManifest m = objectMapper.readValue(json, SidecarManifest.class);
    assertThat(m.fileName()).isEqualTo("a.csv");
    assertThat(m.sizeBytes()).isNull();
    assertThat(m.checksumValue()).isNull();
  }
}
