package com.example.batch.worker.exports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportPayload;
import com.example.batch.worker.exports.domain.ExportWorkerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrepareStepTest {

  @Test
  void execute_returnsInvalid_whenTenantOrPayloadBlank() {
    ObjectMapper objectMapper = new ObjectMapper();
    PlatformFileRuntimeRepository runtimeRepository = mock(PlatformFileRuntimeRepository.class);
    PrepareStep step = new PrepareStep(objectMapper, runtimeRepository);

    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");
    ctx.setRawPayload("");

    var result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_PREPARE_INVALID");
  }

  @Test
  void execute_parsesPayload_andLoadsTemplateConfig_whenTemplateCodeProvided() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    PlatformFileRuntimeRepository runtimeRepository = mock(PlatformFileRuntimeRepository.class);
    PrepareStep step = new PrepareStep(objectMapper, runtimeRepository);

    when(runtimeRepository.loadLatestTemplateConfig(
            eq("t1"), eq("TPL_1"), eq(ExportWorkerType.EXPORT)))
        .thenReturn(
            Map.of(
                "file_format_type", "DELIMITED",
                "naming_rule", "exp_${bizDate}_${tenantId}_${batchNo}_${version}"));

    ExportPayload payload =
        new ExportPayload(
            "FC1",
            "BIZ",
            "TPL_1",
            "B001",
            null,
            null,
            "2026-03-25",
            null,
            Boolean.FALSE,
            null,
            Map.of());
    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");
    ctx.setJobCode("JOB_001");
    ctx.setBizDate("2026-03-25");
    ctx.setRawPayload(objectMapper.writeValueAsString(payload));

    var result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(ctx.getAttributes().get("exportPayload")).isInstanceOf(ExportPayload.class);
    assertThat(ctx.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG))
        .isInstanceOf(Map.class);
    assertThat(String.valueOf(ctx.getAttributes().get("exportFileFormatType")))
        .isEqualTo("DELIMITED");
    assertThat(String.valueOf(ctx.getAttributes().get("fileName")))
        .isEqualTo("exp_2026-03-25_t1_B001_v1");
    assertThat(String.valueOf(ctx.getAttributes().get("objectName"))).contains("outbound/");
    assertThat(ctx.getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT))
        .isInstanceOf(Map.class);
  }

  @Test
  void execute_usesExistingExportPayloadFromAttributes_withoutJsonParse() {
    ObjectMapper objectMapper = new ObjectMapper();
    PlatformFileRuntimeRepository runtimeRepository = mock(PlatformFileRuntimeRepository.class);
    PrepareStep step = new PrepareStep(objectMapper, runtimeRepository);

    when(runtimeRepository.loadLatestTemplateConfig(any(), any(), any()))
        .thenReturn(Map.of("file_format_type", "JSON"));

    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");
    ctx.setJobCode("JOB_001");
    ctx.setBizDate("2026-03-25");
    ctx.setRawPayload("{not-valid-json");
    ctx.getAttributes()
        .put(
            "exportPayload",
            new ExportPayload(
                "FC1",
                "BIZ",
                "TPL_1",
                "B001",
                "fixed.json",
                "obj.json",
                "2026-03-25",
                null,
                Boolean.FALSE,
                null,
                Map.of()));

    var result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(String.valueOf(ctx.getAttributes().get("fileName"))).isEqualTo("fixed.json");
    assertThat(String.valueOf(ctx.getAttributes().get("objectName"))).isEqualTo("obj.json");
  }

  @Test
  void execute_failsWhenBizDateMissingFromPayloadAndContext() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    PlatformFileRuntimeRepository runtimeRepository = mock(PlatformFileRuntimeRepository.class);
    PrepareStep step = new PrepareStep(objectMapper, runtimeRepository);

    ExportPayload payload =
        new ExportPayload(
            "FC1", "BIZ", null, "B001", null, null, null, null, Boolean.FALSE, null, Map.of());
    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");
    ctx.setJobCode("JOB_001");
    ctx.setRawPayload(objectMapper.writeValueAsString(payload));

    var result = step.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_PREPARE_BIZ_DATE_MISSING");
  }

  @Test
  void execute_addsPartitionSuffix_toAllNames_whenMultiPartition() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    PlatformFileRuntimeRepository runtimeRepository = mock(PlatformFileRuntimeRepository.class);
    PrepareStep step = new PrepareStep(objectMapper, runtimeRepository);

    when(runtimeRepository.loadLatestTemplateConfig(any(), any(), any()))
        .thenReturn(Map.of("file_format_type", "DELIMITED"));

    ExportPayload payload =
        new ExportPayload(
            "FC1",
            "BIZ",
            "TPL_1",
            "B001",
            null,
            null,
            "2026-03-25",
            null,
            Boolean.FALSE,
            null,
            Map.of());
    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");
    ctx.setJobCode("JOB_001");
    ctx.setBizDate("2026-03-25");
    ctx.setRawPayload(objectMapper.writeValueAsString(payload));
    ctx.getAttributes().put(PipelineRuntimeKeys.PARTITION_NO, 2);
    ctx.getAttributes().put(PipelineRuntimeKeys.PARTITION_COUNT, 4);

    var result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    // fileName / objectName / tempObjectName 三者均带且仅带一次分片后缀
    assertThat(String.valueOf(ctx.getAttributes().get("fileName"))).contains("_p2of4.csv");
    assertThat(String.valueOf(ctx.getAttributes().get("objectName"))).contains("_p2of4.csv");
    assertThat(String.valueOf(ctx.getAttributes().get("tempObjectName"))).contains("_p2of4");
  }

  @Test
  void execute_noPartitionSuffix_whenSinglePartition() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    PlatformFileRuntimeRepository runtimeRepository = mock(PlatformFileRuntimeRepository.class);
    PrepareStep step = new PrepareStep(objectMapper, runtimeRepository);

    when(runtimeRepository.loadLatestTemplateConfig(any(), any(), any()))
        .thenReturn(Map.of("file_format_type", "DELIMITED"));

    ExportPayload payload =
        new ExportPayload(
            "FC1",
            "BIZ",
            "TPL_1",
            "B001",
            null,
            null,
            "2026-03-25",
            null,
            Boolean.FALSE,
            null,
            Map.of());
    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");
    ctx.setJobCode("JOB_001");
    ctx.setBizDate("2026-03-25");
    ctx.setRawPayload(objectMapper.writeValueAsString(payload));
    // 不设置 PARTITION_* → 默认单片 1/1,文件名应无后缀(向后兼容)

    var result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(String.valueOf(ctx.getAttributes().get("fileName")))
        .isEqualTo("BIZ_2026-03-25_B001.csv");
    assertThat(String.valueOf(ctx.getAttributes().get("fileName"))).doesNotContain("_p");
  }

  @Test
  void execute_tagsExplicitObjectName_whenMultiPartition() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    PlatformFileRuntimeRepository runtimeRepository = mock(PlatformFileRuntimeRepository.class);
    PrepareStep step = new PrepareStep(objectMapper, runtimeRepository);

    when(runtimeRepository.loadLatestTemplateConfig(any(), any(), any()))
        .thenReturn(Map.of("file_format_type", "JSON"));

    // payload 显式 objectName,走 resolveObjectName 的显式分支,需单独打标
    ExportPayload payload =
        new ExportPayload(
            "FC1",
            "BIZ",
            "TPL_1",
            "B001",
            null,
            "custom/report.json",
            "2026-03-25",
            null,
            Boolean.FALSE,
            null,
            Map.of());
    ExportJobContext ctx = new ExportJobContext();
    ctx.setTenantId("t1");
    ctx.setJobCode("JOB_001");
    ctx.setBizDate("2026-03-25");
    ctx.setRawPayload(objectMapper.writeValueAsString(payload));
    ctx.getAttributes().put(PipelineRuntimeKeys.PARTITION_NO, 1);
    ctx.getAttributes().put(PipelineRuntimeKeys.PARTITION_COUNT, 3);

    var result = step.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(String.valueOf(ctx.getAttributes().get("objectName")))
        .isEqualTo("custom/report_p1of3.json");
  }
}
