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

  @Test
  @DisplayName("v1 清单无 fileMapping:hasFileMapping=false,向后兼容")
  void v1ManifestHasNoFileMapping() throws Exception {
    BatchManifest m =
        objectMapper.readValue(
            "{\"schemaVersion\":\"batch-manifest-v1\",\"requiredFiles\":[\"a.csv\"]}",
            BatchManifest.class);
    assertThat(m.fileMapping()).isNull();
    assertThat(m.hasFileMapping()).isFalse();
    assertThat(m.templateCodeFor("a.csv")).isEmpty();
  }

  @Test
  @DisplayName("v2 清单解析 fileMapping:逐文件模板映射 + 可选目标表覆盖")
  void parsesV2FileMapping() throws Exception {
    String json =
        """
        {
          "schemaVersion": "batch-manifest-v2",
          "fileGroupCode": "bundle-daily",
          "bizDate": "2026-06-21",
          "tenantId": "t1",
          "requiredFiles": ["order.csv", "cust.csv"],
          "jobCode": "BUNDLE_IMPORT_DAILY",
          "fileMapping": [
            { "fileName": "order.csv", "templateCode": "TPL_ORDER" },
            { "fileName": "cust.csv", "templateCode": "TPL_CUST", "targetTable": "biz.customer" }
          ]
        }
        """;
    BatchManifest m = objectMapper.readValue(json, BatchManifest.class);
    assertThat(m.jobCode()).isEqualTo("BUNDLE_IMPORT_DAILY");
    assertThat(m.hasFileMapping()).isTrue();
    assertThat(m.fileMapping()).hasSize(2);
    assertThat(m.templateCodeFor("order.csv")).contains("TPL_ORDER");
    assertThat(m.templateCodeFor("cust.csv")).contains("TPL_CUST");
    assertThat(m.fileMapping().get(1).targetTable()).isEqualTo("biz.customer");
    // 未在映射里的文件 → empty
    assertThat(m.templateCodeFor("missing.csv")).isEmpty();
    // 导入束清单无 targetRef
    assertThat(m.targetRefFor("order.csv")).isEmpty();
  }

  @Test
  @DisplayName("v2 分发束清单解析 fileMapping:逐文件下游渠道 targetRef")
  void parsesV2DispatchFileMapping() throws Exception {
    String json =
        """
        {
          "schemaVersion": "batch-manifest-v2",
          "fileGroupCode": "dispatch-eod",
          "bizDate": "2026-06-21",
          "tenantId": "t1",
          "requiredFiles": ["risk.csv", "trade.csv"],
          "jobCode": "BUNDLE_DISPATCH_EOD",
          "fileMapping": [
            { "fileName": "risk.csv", "targetRef": "CH_SFTP" },
            { "fileName": "trade.csv", "targetRef": "CH_OSS" }
          ]
        }
        """;
    BatchManifest m = objectMapper.readValue(json, BatchManifest.class);
    assertThat(m.jobCode()).isEqualTo("BUNDLE_DISPATCH_EOD");
    assertThat(m.hasFileMapping()).isTrue();
    assertThat(m.targetRefFor("risk.csv")).contains("CH_SFTP");
    assertThat(m.targetRefFor("trade.csv")).contains("CH_OSS");
    // 分发束清单无 templateCode
    assertThat(m.templateCodeFor("risk.csv")).isEmpty();
    assertThat(m.targetRefFor("missing.csv")).isEmpty();
  }

  @Test
  @DisplayName("空 fileMapping 数组:hasFileMapping=false")
  void emptyFileMappingIsNotPresent() throws Exception {
    BatchManifest m = objectMapper.readValue("{\"fileMapping\":[]}", BatchManifest.class);
    assertThat(m.hasFileMapping()).isFalse();
  }

  @Test
  @DisplayName("manifest-only 导出束:jobCode + 模板集 + 无 requiredFiles → isManifestOnlyExport")
  void detectsManifestOnlyExportBundle() throws Exception {
    String json =
        """
        {
          "schemaVersion": "batch-manifest-v2",
          "fileGroupCode": "export-eod",
          "bizDate": "2026-06-21",
          "tenantId": "t1",
          "jobCode": "BUNDLE_EXPORT_EOD",
          "fileMapping": [
            { "fileName": "risk_out", "templateCode": "EXP_RISK" },
            { "fileName": "trade_out", "templateCode": "EXP_TRADE" }
          ]
        }
        """;
    BatchManifest m = objectMapper.readValue(json, BatchManifest.class);
    assertThat(m.isManifestOnlyExport()).isTrue();
    assertThat(m.exportTemplateCodes()).containsExactly("EXP_RISK", "EXP_TRADE");
  }

  @Test
  @DisplayName("导入束清单(有 requiredFiles)不是 manifest-only 导出")
  void importBundleIsNotManifestOnlyExport() throws Exception {
    String json =
        """
        {
          "schemaVersion": "batch-manifest-v2",
          "fileGroupCode": "bundle-daily",
          "jobCode": "BUNDLE_IMPORT_DAILY",
          "requiredFiles": ["order.csv"],
          "fileMapping": [ { "fileName": "order.csv", "templateCode": "TPL_ORDER" } ]
        }
        """;
    BatchManifest m = objectMapper.readValue(json, BatchManifest.class);
    assertThat(m.isManifestOnlyExport()).isFalse();
  }

  @Test
  @DisplayName("无 jobCode 或无模板 → 不是 manifest-only 导出")
  void exportRequiresJobCodeAndTemplates() throws Exception {
    BatchManifest noJob =
        objectMapper.readValue(
            "{\"fileMapping\":[{\"fileName\":\"o\",\"templateCode\":\"EXP_A\"}]}",
            BatchManifest.class);
    assertThat(noJob.isManifestOnlyExport()).isFalse();
    BatchManifest noTpl =
        objectMapper.readValue("{\"jobCode\":\"BUNDLE_EXPORT_X\"}", BatchManifest.class);
    assertThat(noTpl.isManifestOnlyExport()).isFalse();
    assertThat(noTpl.exportTemplateCodes()).isEmpty();
  }
}
