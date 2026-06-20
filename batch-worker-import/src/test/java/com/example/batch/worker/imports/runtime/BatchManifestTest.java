package com.example.batch.worker.imports.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BatchManifestTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("解析 batch-manifest-v1:requiredFiles 等字段映射正确")
  void parsesBatchManifest() throws Exception {
    String json =
        """
        {
          "schemaVersion": "batch-manifest-v1",
          "fileGroupCode": "region-daily",
          "bizDate": "2026-06-20",
          "tenantId": "t1",
          "requiredFiles": ["region_BJ_20260620.csv", "region_SH_20260620.csv"]
        }
        """;
    BatchManifest m = objectMapper.readValue(json, BatchManifest.class);
    assertThat(m.fileGroupCode()).isEqualTo("region-daily");
    assertThat(m.tenantId()).isEqualTo("t1");
    assertThat(m.requiredFiles())
        .containsExactly("region_BJ_20260620.csv", "region_SH_20260620.csv");
  }

  @Test
  @DisplayName("未知字段忽略(前向兼容),缺省字段为 null")
  void ignoresUnknownAndAllowsMissing() throws Exception {
    BatchManifest m =
        objectMapper.readValue("{\"fileGroupCode\":\"g\",\"future\":1}", BatchManifest.class);
    assertThat(m.fileGroupCode()).isEqualTo("g");
    assertThat(m.requiredFiles()).isNull();
  }
}
