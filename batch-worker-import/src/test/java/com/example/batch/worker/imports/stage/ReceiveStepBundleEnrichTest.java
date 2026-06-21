package com.example.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.config.WorkerImportPayloadProperties;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ADR-046 文件束:验证 {@link ReceiveStep#enrichFromFileRecord} 把既有 file_record 的存储坐标回填到 payload
 * 为空的字段,且不覆盖 payload 已带的字段(对既有 existing-file 导入流零影响)。
 */
@ExtendWith(MockitoExtension.class)
class ReceiveStepBundleEnrichTest {

  @Mock private PlatformFileRuntimeRepository runtimeRepository;
  @Mock private BatchSecurityProperties batchSecurityProperties;

  private ReceiveStep receiveStep;

  @BeforeEach
  void setUp() {
    receiveStep =
        new ReceiveStep(
            runtimeRepository,
            batchSecurityProperties,
            new ObjectMapper(),
            new WorkerImportPayloadProperties());
  }

  private ImportPayload payloadWithTemplateOnly(String templateCode) {
    return new ImportPayload(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        templateCode,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Map.of());
  }

  private Map<String, Object> fileRecord() {
    Map<String, Object> fr = new LinkedHashMap<>();
    fr.put("storage_path", "ingress/t1/g1/risk.csv");
    fr.put("storage_bucket", "batch-ingress");
    fr.put("storage_type", "MINIO");
    fr.put("file_format_type", "CSV");
    fr.put("charset", "UTF-8");
    fr.put("file_name", "risk.csv");
    fr.put("original_file_name", "risk-2026.csv");
    fr.put("file_code", "FR-1");
    fr.put("biz_type", "RISK");
    return fr;
  }

  @Test
  void enrichFromFileRecord_backfillsStorageCoordinatesForBundlePartition() {
    ImportPayload enriched =
        receiveStep.enrichFromFileRecord(payloadWithTemplateOnly("RISK_IMPORT_V2"), fileRecord());

    assertThat(enriched.storagePath()).isEqualTo("ingress/t1/g1/risk.csv");
    assertThat(enriched.storageBucket()).isEqualTo("batch-ingress");
    assertThat(enriched.storageType()).isEqualTo("MINIO");
    assertThat(enriched.fileFormatType()).isEqualTo("CSV");
    assertThat(enriched.charset()).isEqualTo("UTF-8");
    assertThat(enriched.fileName()).isEqualTo("risk.csv");
    assertThat(enriched.originalFileName()).isEqualTo("risk-2026.csv");
    // payload 已带的 templateCode 不被回填逻辑触碰
    assertThat(enriched.templateCode()).isEqualTo("RISK_IMPORT_V2");
  }

  @Test
  void enrichFromFileRecord_doesNotOverrideFieldsPayloadAlreadyCarries() {
    ImportPayload payload =
        new ImportPayload(
            null,
            null,
            null,
            null,
            "TSV",
            "GBK",
            null,
            null,
            null,
            null,
            null,
            "TYPE_A",
            "my/own/path.tsv",
            "my-bucket",
            "T1",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Map.of());

    ImportPayload enriched = receiveStep.enrichFromFileRecord(payload, fileRecord());

    // payload 自带的存储信息优先,file_record 不得覆盖
    assertThat(enriched.storagePath()).isEqualTo("my/own/path.tsv");
    assertThat(enriched.storageBucket()).isEqualTo("my-bucket");
    assertThat(enriched.storageType()).isEqualTo("TYPE_A");
    assertThat(enriched.fileFormatType()).isEqualTo("TSV");
    assertThat(enriched.charset()).isEqualTo("GBK");
  }

  @Test
  void enrichFromFileRecord_nullOrEmptyFileRecordReturnsPayloadUnchanged() {
    ImportPayload payload = payloadWithTemplateOnly("RISK_IMPORT_V2");

    assertThat(receiveStep.enrichFromFileRecord(payload, null)).isSameAs(payload);
    assertThat(receiveStep.enrichFromFileRecord(payload, Map.of())).isSameAs(payload);
    assertThat(receiveStep.enrichFromFileRecord(payload, "not-a-map")).isSameAs(payload);
  }
}
